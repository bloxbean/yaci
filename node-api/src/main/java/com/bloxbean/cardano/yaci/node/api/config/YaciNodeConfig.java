package com.bloxbean.cardano.yaci.node.api.config;

import com.bloxbean.cardano.yaci.core.common.Constants;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Configuration for YaciNode (renamed from HybridNodeConfig).
 * Provides comprehensive configuration options for both client and server modes.
 */
@Data
@Builder
public class YaciNodeConfig implements NodeConfig {

    // Remote node configuration (client mode)
    private String remoteHost;
    private int remotePort;
    private long protocolMagic;

    // Server configuration
    private int serverPort;
    private boolean enableServer;
    private boolean enableClient;

    // Storage configuration
    private boolean useRocksDB;
    private String rocksDBPath;

    // Sync configuration
    private long fullSyncThreshold; // If behind by more than this many slots, do full sync

    // Pipeline configuration
    private boolean enablePipelinedSync; // Enable/disable pipelined sync (false = use sequential sync)
    private int headerPipelineDepth;
    private int bodyBatchSize;
    private int maxParallelBodies;
    private boolean enableSelectiveBodyFetch;
    private int selectiveBodyFetchRatio; // Fetch every Nth body during bulk sync (0 = all, 10 = every 10th)

    // Monitoring configuration
    private boolean enableMonitoring;
    private int monitoringPort;

    private long syncStartSlot;
    private String syncStartBlockHash;

    // Block producer configuration (devnet mode)
    private boolean enableBlockProducer;
    private boolean devMode;
    private int blockTimeMillis;
    private boolean lazyBlockProduction;
    private long genesisTimestamp;
    private int slotLengthMillis;
    private String shelleyGenesisHash;     // Hex-encoded blake2b-256 of shelley-genesis.json (optional, overrides file hashing)
    private String shelleyGenesisFile;     // Path to shelley-genesis.json
    private String byronGenesisFile;       // Path to byron-genesis.json (optional, for relay mode)
    private String alonzoGenesisFile;      // Path to alonzo-genesis.json (optional)
    private String conwayGenesisFile;      // Path to conway-genesis.json (optional)
    private String protocolParametersFile; // Path to protocol params JSON
    private boolean txEvaluationEnabled;   // Enable ledger rule validation for submitted transactions

    // Block producer crypto key files (for signed blocks)
    private String vrfSkeyFile;            // Path to VRF secret key file (TextEnvelope JSON)
    private String kesSkeyFile;            // Path to KES secret key file (TextEnvelope JSON)
    private String opCertFile;             // Path to operational certificate file (TextEnvelope JSON)

    // Slot leader mode (public network block production)
    private boolean slotLeaderMode;                // Enable Praos slot leader selection instead of devnet fixed-interval
    private String stakeDataProviderUrl;            // yaci-store base URL for stake data (e.g. http://localhost:8080/api/v1)
    private String initialEpochNonce;               // Hex-encoded 32-byte nonce for bootstrap seeding
    @Builder.Default
    private int initialEpoch = -1;                  // Epoch number for the seed nonce (-1 = not set)

    // Bootstrap configuration (lightweight relay mode)
    private boolean enableBootstrap;
    @Builder.Default
    private long bootstrapBlockNumber = -1;  // -1 = "latest"
    private List<String> bootstrapAddresses;
    private List<BootstrapOutpointConfig> bootstrapUtxos;
    private String bootstrapProvider;       // "blockfrost" or "koios"
    private String bootstrapBlockfrostApiKey;
    private String bootstrapBlockfrostBaseUrl;
    private String bootstrapKoiosBaseUrl;
    private String network;                 // "mainnet", "preprod", "preview" — used for provider URL auto-detection

    // Genesis-derived configuration
    @Builder.Default
    private long epochLength = 432000;     // Slots per epoch (from shelley-genesis.json epochLength)

    // Implement NodeConfig interface
    @Override
    public boolean isClientEnabled() {
        return enableClient;
    }

    @Override
    public boolean isServerEnabled() {
        return enableServer;
    }

    /**
     * Create a default configuration for preprod
     */
    public static YaciNodeConfig preprodDefault() {
        return YaciNodeConfig.builder()
                .remoteHost("localhost")
                .remotePort(32000)
                .protocolMagic(Constants.PREPROD_PROTOCOL_MAGIC)
                .serverPort(13337)
                .enableServer(true)
                .enableClient(true)
                .useRocksDB(true)
                .rocksDBPath("./chainstate")
                .fullSyncThreshold(1800) // 30 minutes worth of slots
                .enablePipelinedSync(true)  // Changed to false for sequential sync by default
                .headerPipelineDepth(200)
                .bodyBatchSize(200)
                .maxParallelBodies(50)
                .enableSelectiveBodyFetch(false)  // Disabled for sequential mode
                .selectiveBodyFetchRatio(0)
                .enableMonitoring(false)
                .monitoringPort(8080)
                .build();
    }

    /**
     * Create a default configuration for mainnet
     */
    public static YaciNodeConfig mainnetDefault() {
        return YaciNodeConfig.builder()
                .remoteHost(Constants.MAINNET_PUBLIC_RELAY_ADDR)
                .remotePort(Constants.MAINNET_PUBLIC_RELAY_PORT)
                .protocolMagic(Constants.MAINNET_PROTOCOL_MAGIC)
                .serverPort(13337)
                .enableServer(true)
                .enableClient(true)
                .useRocksDB(true)
                .rocksDBPath("./chainstate")
                .fullSyncThreshold(1800) // 30 minutes worth of slots
                .enablePipelinedSync(true)
                .headerPipelineDepth(300)
                .bodyBatchSize(100)
                .maxParallelBodies(15)
                .enableSelectiveBodyFetch(true)
                .selectiveBodyFetchRatio(5)  // More aggressive for mainnet
                .enableMonitoring(false)
                .monitoringPort(8080)
                .build();
    }

    /**
     * Create a server-only configuration (no client sync)
     */
    public static YaciNodeConfig serverOnly(int serverPort) {
        return YaciNodeConfig.builder()
                .remoteHost(null)
                .remotePort(0)
                .protocolMagic(Constants.PREPROD_PROTOCOL_MAGIC)
                .serverPort(serverPort)
                .enableServer(true)
                .enableClient(false)
                .useRocksDB(false)
                .rocksDBPath(null)
                .fullSyncThreshold(1800)
                .enablePipelinedSync(false)  // Server-only doesn't sync
                .headerPipelineDepth(0)
                .bodyBatchSize(0)
                .maxParallelBodies(0)
                .enableSelectiveBodyFetch(false)
                .selectiveBodyFetchRatio(0)
                .enableMonitoring(false)
                .monitoringPort(8080)
                .build();
    }

    /**
     * Create a client-only configuration (no server)
     */
    public static YaciNodeConfig clientOnly(String remoteHost, int remotePort, long protocolMagic) {
        return YaciNodeConfig.builder()
                .remoteHost(remoteHost)
                .remotePort(remotePort)
                .protocolMagic(protocolMagic)
                .serverPort(0)
                .enableServer(false)
                .enableClient(true)
                .useRocksDB(true)
                .rocksDBPath("./chainstate")
                .fullSyncThreshold(1800)
                .enablePipelinedSync(true)
                .headerPipelineDepth(150)
                .bodyBatchSize(30)
                .maxParallelBodies(8)
                .enableSelectiveBodyFetch(false)  // Fetch all for client-only
                .selectiveBodyFetchRatio(0)
                .enableMonitoring(false)
                .monitoringPort(8080)
                .build();
    }

    /**
     * Create a default configuration for a standalone devnet with block production.
     * No upstream node needed — produces its own blocks.
     */
    public static YaciNodeConfig devnetDefault(int serverPort) {
        return YaciNodeConfig.builder()
                .remoteHost(null)
                .remotePort(0)
                .protocolMagic(42) // Custom devnet magic
                .serverPort(serverPort)
                .enableServer(true)
                .enableClient(false)
                .enableBlockProducer(true)
                .devMode(true)
                .blockTimeMillis(0)
                .lazyBlockProduction(false)
                .genesisTimestamp(0)
                .slotLengthMillis(0)
                .useRocksDB(false)
                .rocksDBPath(null)
                .fullSyncThreshold(0)
                .enablePipelinedSync(false)
                .headerPipelineDepth(0)
                .bodyBatchSize(0)
                .maxParallelBodies(0)
                .enableSelectiveBodyFetch(false)
                .selectiveBodyFetchRatio(0)
                .enableMonitoring(false)
                .monitoringPort(8080)
                .build();
    }

    /**
     * Create a configuration for performance testing with pipeline toggle
     */
    public static YaciNodeConfig performanceTestConfig(String remoteHost, int remotePort, long protocolMagic,
                                                       int serverPort, boolean useRocksDB, boolean enablePipeline) {
        return YaciNodeConfig.builder()
                .remoteHost(remoteHost)
                .remotePort(remotePort)
                .protocolMagic(protocolMagic)
                .serverPort(serverPort)
                .enableServer(false)  // Disable server for pure sync testing
                .enableClient(true)
                .useRocksDB(useRocksDB)
                .rocksDBPath(useRocksDB ? "./perf-test-chainstate" : null)
                .fullSyncThreshold(100)
                .enablePipelinedSync(enablePipeline)
                .headerPipelineDepth(enablePipeline ? 50 : 0)
                .bodyBatchSize(enablePipeline ? 10 : 0)
                .maxParallelBodies(enablePipeline ? 5 : 0)
                .enableSelectiveBodyFetch(false)  // Fetch all for fair comparison
                .selectiveBodyFetchRatio(0)
                .enableMonitoring(false)
                .monitoringPort(8080)
                .build();
    }

    /**
     * Create a configuration for testing with custom parameters
     */
    public static YaciNodeConfig testConfig(String remoteHost, int remotePort, long protocolMagic,
                                            int serverPort, boolean useRocksDB) {
        return YaciNodeConfig.builder()
                .remoteHost(remoteHost)
                .remotePort(remotePort)
                .protocolMagic(protocolMagic)
                .serverPort(serverPort)
                .enableServer(true)
                .enableClient(true)
                .useRocksDB(useRocksDB)
                .rocksDBPath(useRocksDB ? "./test-chainstate" : null)
                .fullSyncThreshold(100) // Lower threshold for testing
                .enablePipelinedSync(true)
                .headerPipelineDepth(20)  // Smaller values for testing
                .bodyBatchSize(5)
                .maxParallelBodies(2)
                .enableSelectiveBodyFetch(true)
                .selectiveBodyFetchRatio(4)  // Test selective fetching
                .enableMonitoring(false)
                .monitoringPort(8080)
                .build();
    }

    /**
     * Validate the configuration
     */
    @Override
    public void validate() {
        if (enableClient) {
            if (remoteHost == null || remoteHost.trim().isEmpty()) {
                throw new IllegalArgumentException("Remote host must be specified when client is enabled");
            }
            if (remotePort <= 0 || remotePort > 65535) {
                throw new IllegalArgumentException("Remote port must be between 1 and 65535");
            }
        }

        if (enableServer) {
            if (serverPort <= 0 || serverPort > 65535) {
                throw new IllegalArgumentException("Server port must be between 1 and 65535");
            }
        }

        if (!enableClient && !enableServer && !enableBlockProducer) {
            throw new IllegalArgumentException("At least one of client, server, or block producer must be enabled");
        }

        if (enableBlockProducer) {
            if (!slotLeaderMode && enableClient) {
                throw new IllegalArgumentException("Devnet block producer mode cannot be used with client mode");
            }
            if (!enableServer) {
                throw new IllegalArgumentException("Block producer mode requires server to be enabled");
            }
            // blockTimeMillis == 0 is valid: means auto-derive from genesis in YaciNode
            // slotLengthMillis == 0 is valid: means auto-derive from genesis in YaciNode
        }

        if (slotLeaderMode) {
            if (!enableBlockProducer) {
                throw new IllegalArgumentException("Slot leader mode requires block producer to be enabled");
            }
            if (!enableClient) {
                throw new IllegalArgumentException("Slot leader mode requires client to be enabled (to sync chain)");
            }
            if (stakeDataProviderUrl == null || stakeDataProviderUrl.isBlank()) {
                throw new IllegalArgumentException("Slot leader mode requires stake-data-provider-url");
            }
            if (vrfSkeyFile == null || vrfSkeyFile.isBlank()
                    || kesSkeyFile == null || kesSkeyFile.isBlank()
                    || opCertFile == null || opCertFile.isBlank()) {
                throw new IllegalArgumentException("Slot leader mode requires VRF, KES, and OpCert key files");
            }
        }

        if (devMode && !enableBlockProducer) {
            throw new IllegalArgumentException("Dev mode requires block producer to be enabled");
        }

        if (useRocksDB && (rocksDBPath == null || rocksDBPath.trim().isEmpty())) {
            throw new IllegalArgumentException("RocksDB path must be specified when RocksDB is enabled");
        }

        if (fullSyncThreshold < 0) {
            throw new IllegalArgumentException("Full sync threshold must be non-negative");
        }

        if (enableMonitoring && (monitoringPort <= 0 || monitoringPort > 65535)) {
            throw new IllegalArgumentException("Monitoring port must be between 1 and 65535");
        }

        // Validate pipeline configuration (only when pipelining is enabled)
        if (enableClient && enablePipelinedSync) {
            if (headerPipelineDepth <= 0) {
                throw new IllegalArgumentException("Header pipeline depth must be positive when pipelining is enabled");
            }
            if (bodyBatchSize <= 0) {
                throw new IllegalArgumentException("Body batch size must be positive when pipelining is enabled");
            }
            if (maxParallelBodies <= 0) {
                throw new IllegalArgumentException("Max parallel bodies must be positive when pipelining is enabled");
            }
            if (selectiveBodyFetchRatio < 0) {
                throw new IllegalArgumentException("Selective body fetch ratio must be non-negative");
            }
        }
    }

    @Override
    public String toString() {
        return String.format(
                "YaciNodeConfig{client=%s, server=%s, remote=%s:%d, serverPort=%d, storage=%s, magic=%d}",
                enableClient, enableServer, remoteHost, remotePort, serverPort,
                useRocksDB ? "RocksDB" : "Memory", protocolMagic
        );
    }
}
