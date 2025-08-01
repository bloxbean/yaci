package com.bloxbean.cardano.yaci.node;

import com.bloxbean.cardano.yaci.core.common.Constants;
import lombok.Builder;
import lombok.Data;

/**
 * Configuration for HybridYaciNode
 */
@Data
@Builder
public class HybridNodeConfig {

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

    /**
     * Create a default configuration for preprod
     */
    public static HybridNodeConfig preprodDefault() {
        return HybridNodeConfig.builder()
                .remoteHost("localhost")
                .remotePort(32000)
                .protocolMagic(Constants.PREPROD_PROTOCOL_MAGIC)
                .serverPort(13337)
                .enableServer(true)
                .enableClient(true)
                .useRocksDB(true)
                .rocksDBPath("./chainstate")
                .fullSyncThreshold(1800) // 30 minutes worth of slots
                .enablePipelinedSync(false)  // Changed to false for sequential sync by default
                .headerPipelineDepth(200)
                .bodyBatchSize(50)
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
    public static HybridNodeConfig mainnetDefault() {
        return HybridNodeConfig.builder()
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
    public static HybridNodeConfig serverOnly(int serverPort) {
        return HybridNodeConfig.builder()
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
    public static HybridNodeConfig clientOnly(String remoteHost, int remotePort, long protocolMagic) {
        return HybridNodeConfig.builder()
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
     * Create a configuration for performance testing with pipeline toggle
     */
    public static HybridNodeConfig performanceTestConfig(String remoteHost, int remotePort, long protocolMagic,
                                                        int serverPort, boolean useRocksDB, boolean enablePipeline) {
        return HybridNodeConfig.builder()
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
    public static HybridNodeConfig testConfig(String remoteHost, int remotePort, long protocolMagic,
                                              int serverPort, boolean useRocksDB) {
        return HybridNodeConfig.builder()
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

        if (!enableClient && !enableServer) {
            throw new IllegalArgumentException("At least one of client or server must be enabled");
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
                "HybridNodeConfig{client=%s, server=%s, remote=%s:%d, serverPort=%d, storage=%s, magic=%d}",
                enableClient, enableServer, remoteHost, remotePort, serverPort,
                useRocksDB ? "RocksDB" : "Memory", protocolMagic
        );
    }
}
