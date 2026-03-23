package com.bloxbean.cardano.yaci.node.app.e2e.haskellsync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Downloads and manages a Haskell cardano-node binary for integration testing.
 * Caches the binary at ~/.yaci/test/cardano-node-{version}/.
 */
public class CardanoNodeManager {

    private static final Logger log = LoggerFactory.getLogger(CardanoNodeManager.class);

    private static final String DEFAULT_VERSION = "10.5.2";
    private static final Pattern CHAIN_EXTENDED_PATTERN =
            Pattern.compile("Chain extended.*slot (\\d+)");

    private final Path workDir;
    private final String version;
    private final StringBuilder logOutput = new StringBuilder();
    private Process process;

    public CardanoNodeManager(Path workDir) {
        this.workDir = workDir;
        this.version = resolveVersion();
    }

    private static String resolveVersion() {
        String v = System.getProperty("cardano.node.version");
        if (v == null || v.isBlank()) {
            v = System.getenv("CARDANO_NODE_VERSION");
        }
        return (v != null && !v.isBlank()) ? v : DEFAULT_VERSION;
    }

    /**
     * Returns the path to the cardano-node binary, downloading if necessary.
     * The binary stays in the extracted bin/ directory alongside its shared libraries
     * (liblmdb.so, libsodium, etc.) which are loaded via @executable_path.
     */
    public Path ensureBinary() throws Exception {
        Path cacheDir = Path.of(System.getProperty("user.home"), ".yaci", "test",
                "cardano-node-" + version);

        // The binary lives in bin/ alongside its shared libs
        Path binDir = cacheDir.resolve("bin");
        Path binary = binDir.resolve("cardano-node");

        if (Files.isExecutable(binary)) {
            log.info("Using cached cardano-node {} at {}", version, binary);
            return binary;
        }

        Files.createDirectories(cacheDir);
        String platform = detectPlatform();
        String url = "https://github.com/IntersectMBO/cardano-node/releases/download/"
                + version + "/cardano-node-" + version + "-" + platform + ".tar.gz";

        log.info("Downloading cardano-node {} from {}", version, url);
        Path tarball = cacheDir.resolve("cardano-node.tar.gz");

        try (InputStream in = URI.create(url).toURL().openStream()) {
            Files.copy(in, tarball, StandardCopyOption.REPLACE_EXISTING);
        }

        // Extract — preserves bin/ directory structure so shared libs stay next to binary
        ProcessBuilder pb = new ProcessBuilder("tar", "xzf", tarball.toString(), "-C", cacheDir.toString())
                .redirectErrorStream(true);
        Process p = pb.start();
        p.getInputStream().transferTo(OutputStream.nullOutputStream());
        int exit = p.waitFor();
        if (exit != 0) {
            throw new RuntimeException("Failed to extract cardano-node tarball, exit code: " + exit);
        }

        if (!Files.exists(binary)) {
            // Try to find it recursively
            try (var stream = Files.walk(cacheDir, 3)) {
                Path found = stream
                        .filter(p2 -> p2.getFileName().toString().equals("cardano-node") && !Files.isDirectory(p2))
                        .findFirst()
                        .orElseThrow(() -> new RuntimeException(
                                "cardano-node binary not found after extraction in " + cacheDir));
                binary = found;
            }
        }

        binary.toFile().setExecutable(true);
        Files.deleteIfExists(tarball);
        log.info("cardano-node {} ready at {}", version, binary);
        return binary;
    }

    /**
     * Directory where genesis files should be placed for the Haskell node.
     */
    public Path getGenesisDir() {
        return workDir.resolve("haskell-genesis");
    }

    /**
     * Starts cardano-node connecting to Yaci on the given n2n port.
     */
    public void start(int yaciN2nPort) throws Exception {
        Path binary = ensureBinary();
        Path genesisDir = getGenesisDir();
        Files.createDirectories(genesisDir);

        Path dbDir = workDir.resolve("hdb");
        Files.createDirectories(dbDir);

        // Unix socket path must be <= 104 chars. Use a short path under /tmp if needed.
        Path socketPath = dbDir.resolve("node.socket");
        if (socketPath.toString().length() > 100) {
            Path shortSocketDir = Files.createTempDirectory("cn");
            socketPath = shortSocketDir.resolve("n.sock");
        }

        // Write configuration.json
        Path configFile = workDir.resolve("configuration.json");
        Files.writeString(configFile, buildConfigurationJson());

        // Write topology.json
        Path topologyFile = workDir.resolve("topology.json");
        Files.writeString(topologyFile, buildTopologyJson(yaciN2nPort));

        ProcessBuilder pb = new ProcessBuilder(
                binary.toString(), "run",
                "--topology", topologyFile.toString(),
                "--database-path", dbDir.toString(),
                "--socket-path", socketPath.toString(),
                "--host-addr", "0.0.0.0",
                "--port", "0",  // ephemeral port — we don't need inbound connections
                "--config", configFile.toString()
        );
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);

        log.info("Starting cardano-node {} (connecting to Yaci port {})", version, yaciN2nPort);
        process = pb.start();

        // Capture stdout/stderr asynchronously
        Thread logReader = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (logOutput) {
                        logOutput.append(line).append("\n");
                        // Keep last 50000 chars to avoid unbounded memory
                        if (logOutput.length() > 50000) {
                            logOutput.delete(0, logOutput.length() - 40000);
                        }
                    }
                    log.trace("[cardano-node] {}", line);
                }
            } catch (IOException e) {
                if (process.isAlive()) {
                    log.warn("Error reading cardano-node output", e);
                }
            }
        }, "cardano-node-log-reader");
        logReader.setDaemon(true);
        logReader.start();
    }

    /**
     * Waits until the Haskell node has extended its chain to at least the given slot.
     */
    public void waitForChainExtended(long targetSlot, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            long latest = getLatestSyncedSlot();
            if (latest >= targetSlot) {
                log.info("Haskell node reached slot {} (target: {})", latest, targetSlot);
                return;
            }
            if (!process.isAlive()) {
                throw new RuntimeException("cardano-node process died unexpectedly (exit: " + process.exitValue() + ").\nLog:\n" + getLogTail());
            }
            Thread.sleep(500);
        }
        throw new RuntimeException("Timed out waiting for Haskell node to reach slot " + targetSlot
                + ". Latest synced: " + getLatestSyncedSlot() + "\nLog tail:\n" + getLogTail());
    }

    /**
     * Parses the latest "Chain extended" slot from the node's log output.
     */
    public long getLatestSyncedSlot() {
        String logs;
        synchronized (logOutput) {
            logs = logOutput.toString();
        }
        long maxSlot = -1;
        Matcher m = CHAIN_EXTENDED_PATTERN.matcher(logs);
        while (m.find()) {
            long slot = Long.parseLong(m.group(1));
            if (slot > maxSlot) {
                maxSlot = slot;
            }
        }
        return maxSlot;
    }

    public void stop() {
        if (process != null && process.isAlive()) {
            log.info("Stopping cardano-node");
            process.destroy();
            try {
                process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private String getLogTail() {
        synchronized (logOutput) {
            int len = logOutput.length();
            return len > 3000 ? logOutput.substring(len - 3000) : logOutput.toString();
        }
    }

    private static String detectPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac") || os.contains("darwin")) {
            return "macos";
        } else if (os.contains("linux")) {
            return "linux";
        }
        throw new UnsupportedOperationException("Unsupported OS for cardano-node download: " + os);
    }

    private String buildConfigurationJson() {
        Path genesisDir = getGenesisDir();
        // Use relative paths from workDir
        String shelleyPath = workDir.relativize(genesisDir.resolve("shelley-genesis.json")).toString();
        String byronPath = workDir.relativize(genesisDir.resolve("byron-genesis.json")).toString();
        String alonzoPath = workDir.relativize(genesisDir.resolve("alonzo-genesis.json")).toString();
        String conwayPath = workDir.relativize(genesisDir.resolve("conway-genesis.json")).toString();

        return """
                {
                  "AlonzoGenesisFile": "./%s",
                  "ByronGenesisFile": "./%s",
                  "ConwayGenesisFile": "./%s",
                  "EnableP2P": true,
                  "LastKnownBlockVersion-Alt": 0,
                  "LastKnownBlockVersion-Major": 2,
                  "LastKnownBlockVersion-Minor": 0,
                  "LedgerDB": {
                    "Backend": "V2InMemory",
                    "NumOfDiskSnapshots": 2,
                    "QueryBatchSize": 100000,
                    "SnapshotInterval": 4320
                  },
                  "PeerSharing": true,
                  "Protocol": "Cardano",
                  "RequiresNetworkMagic": "RequiresMagic",
                  "ShelleyGenesisFile": "./%s",
                  "TestShelleyHardForkAtEpoch": 0,
                  "TestAllegraHardForkAtEpoch": 0,
                  "TestMaryHardForkAtEpoch": 0,
                  "TestAlonzoHardForkAtEpoch": 0,
                  "TestBabbageHardForkAtEpoch": 0,
                  "TestConwayHardForkAtEpoch": 0,
                  "ExperimentalHardForksEnabled": true,
                  "ExperimentalProtocolsEnabled": true,
                  "TargetNumberOfActivePeers": 20,
                  "TargetNumberOfEstablishedPeers": 40,
                  "TargetNumberOfKnownPeers": 100,
                  "TargetNumberOfRootPeers": 100,
                  "TraceBlockFetchClient": true,
                  "TraceBlockFetchDecisions": true,
                  "TraceBlockFetchProtocol": true,
                  "TraceChainDb": true,
                  "TraceChainSyncClient": true,
                  "TraceConnectionManager": true,
                  "TraceDiffusionInitialization": true,
                  "TraceHandshake": true,
                  "TraceInboundGovernor": true,
                  "TraceLedgerPeers": true,
                  "TraceLocalRootPeers": true,
                  "TraceMempool": true,
                  "TracePeerSelection": true,
                  "TracePeerSelectionActions": true,
                  "TracePublicRootPeers": true,
                  "TraceServer": true,
                  "TracingVerbosity": "NormalVerbosity",
                  "TurnOnLogMetrics": false,
                  "TurnOnLogging": true,
                  "UseTraceDispatcher": false,
                  "MinBigLedgerPeersForTrustedState": 0,
                  "defaultBackends": ["KatipBK"],
                  "defaultScribes": [["StdoutSK", "stdout"]],
                  "minSeverity": "Info",
                  "options": {
                    "mapBackends": {},
                    "mapSubtrace": {
                      "cardano.node.metrics": {"subtrace": "Neutral"}
                    }
                  },
                  "rotation": {
                    "rpKeepFilesNum": 10,
                    "rpLogLimitBytes": 5000000,
                    "rpMaxAgeHours": 24
                  },
                  "setupBackends": ["KatipBK"],
                  "setupScribes": [{
                    "scFormat": "ScText",
                    "scKind": "StdoutSK",
                    "scName": "stdout",
                    "scRotation": null
                  }],
                  "TraceChainDB": true
                }
                """.formatted(alonzoPath, byronPath, conwayPath, shelleyPath);
    }

    private String buildTopologyJson(int yaciPort) {
        return """
                {
                  "bootstrapPeers": [
                    {"address": "127.0.0.1", "port": %d}
                  ],
                  "localRoots": [
                    {
                      "accessPoints": [
                        {"address": "127.0.0.1", "port": %d}
                      ],
                      "valency": 1
                    }
                  ],
                  "publicRoots": [],
                  "useLedgerAfterSlot": -1
                }
                """.formatted(yaciPort, yaciPort);
    }
}
