package com.bloxbean.cardano.yaci.node.app.e2e.haskellsync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Manages a Yaci uber-jar process for integration testing.
 */
public class YaciNodeManager {

    private static final Logger log = LoggerFactory.getLogger(YaciNodeManager.class);

    private final Path workDir;
    private final Path uberJarPath;
    private final int httpPort;
    private final int n2nPort;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private Process process;

    public YaciNodeManager(Path workDir, Path uberJarPath) throws IOException {
        this.workDir = workDir;
        this.uberJarPath = uberJarPath;
        this.httpPort = findAvailablePort();
        this.n2nPort = findAvailablePort();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public int getHttpPort() {
        return httpPort;
    }

    public int getN2nPort() {
        return n2nPort;
    }

    /**
     * Starts the Yaci node with the devnet profile and optional extra system properties.
     */
    public void start(String... extraProps) throws IOException {
        Path chainstateDir = workDir.resolve("chainstate");
        // Copy devnet config directory into workDir so genesis files are available
        Path nodeAppDir = uberJarPath.getParent().getParent(); // build/ -> node-app/
        Path configSrc = nodeAppDir.resolve("config");
        Path configDst = workDir.resolve("config");
        if (!Files.exists(configDst)) {
            copyDirectory(configSrc, configDst);
        }

        List<String> cmd = new ArrayList<>();
        cmd.add("java");
        cmd.add("-Dquarkus.profile=devnet");
        cmd.add("-Dquarkus.http.port=" + httpPort);
        cmd.add("-Dyaci.node.server.port=" + n2nPort);
        cmd.add("-Dyaci.node.storage.path=" + chainstateDir);
        cmd.add("-Dyaci.node.block-producer.block-time-millis=200");

        for (String prop : extraProps) {
            if (prop.startsWith("-D") || prop.startsWith("--")) {
                cmd.add(prop);
            } else if (prop.contains("=")) {
                cmd.add("-D" + prop);
            }
        }

        cmd.add("-jar");
        cmd.add(uberJarPath.toString());

        log.info("Starting Yaci node: httpPort={}, n2nPort={}", httpPort, n2nPort);
        log.debug("Command: {}", String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);

        process = pb.start();

        // Pipe output to logger
        Thread logReader = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.trace("[yaci] {}", line);
                }
            } catch (IOException e) {
                if (process.isAlive()) {
                    log.warn("Error reading Yaci output", e);
                }
            }
        }, "yaci-log-reader");
        logReader.setDaemon(true);
        logReader.start();
    }

    /**
     * Waits for the Yaci node to become ready via the health endpoint.
     */
    public void waitForReady(long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        String healthUrl = "http://localhost:" + httpPort + "/q/health/ready";

        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                throw new RuntimeException("Yaci process died during startup (exit: " + process.exitValue() + ")");
            }
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(healthUrl))
                        .timeout(Duration.ofSeconds(2))
                        .GET()
                        .build();
                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    log.info("Yaci node ready on port {}", httpPort);
                    return;
                }
            } catch (Exception e) {
                // not ready yet
            }
            Thread.sleep(500);
        }
        throw new RuntimeException("Timed out waiting for Yaci node to become ready");
    }

    /**
     * Gets the current chain tip from Yaci.
     */
    public JsonNode getTip() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + httpPort + "/api/v1/node/tip"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Failed to get tip: " + resp.statusCode() + " " + resp.body());
        }
        return objectMapper.readTree(resp.body());
    }

    /**
     * POST to a devnet API path with a JSON body.
     */
    public JsonNode post(String path, String jsonBody) throws Exception {
        String url = "http://localhost:" + httpPort + "/api/v1/devnet/" + path;
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new RuntimeException("POST " + path + " failed: " + resp.statusCode() + " " + resp.body());
        }
        return objectMapper.readTree(resp.body());
    }

    /**
     * Copies genesis files from Yaci's config directory to the target directory.
     */
    public void copyGenesisTo(Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        Path devnetConfig = workDir.resolve("config").resolve("network").resolve("devnet");
        String[] genesisFiles = {
                "shelley-genesis.json", "byron-genesis.json",
                "alonzo-genesis.json", "conway-genesis.json"
        };
        for (String name : genesisFiles) {
            Path src = devnetConfig.resolve(name);
            if (Files.exists(src)) {
                Files.copy(src, targetDir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    public void stop() {
        if (process != null && process.isAlive()) {
            log.info("Stopping Yaci node");
            process.destroy();
            try {
                process.waitFor(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static int findAvailablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        try (var walk = Files.walk(source)) {
            walk.forEach(src -> {
                try {
                    Path dst = target.resolve(source.relativize(src));
                    if (Files.isDirectory(src)) {
                        Files.createDirectories(dst);
                    } else {
                        Files.createDirectories(dst.getParent());
                        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }
}
