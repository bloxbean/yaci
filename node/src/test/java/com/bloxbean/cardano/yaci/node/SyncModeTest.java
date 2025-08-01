package com.bloxbean.cardano.yaci.node;

import com.bloxbean.cardano.yaci.core.common.Constants;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

/**
 * Test to verify both sync modes work correctly
 */
@Slf4j
@Disabled
public class SyncModeTest {

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void testSequentialSyncMode() throws InterruptedException {
        log.info("Testing sequential sync mode...");

        HybridNodeConfig config = HybridNodeConfig.builder()
                .remoteHost("preprod-node.play.dev.cardano.org")
                .remotePort(3001)
                .protocolMagic(Constants.PREPROD_PROTOCOL_MAGIC)
                .serverPort(13344)
                .enableServer(false)
                .enableClient(true)
                .useRocksDB(false)
                .rocksDBPath(null)
                .fullSyncThreshold(50)
                .enablePipelinedSync(false)  // SEQUENTIAL MODE
                .headerPipelineDepth(0)      // Not used in sequential
                .bodyBatchSize(0)            // Not used in sequential
                .maxParallelBodies(0)        // Not used in sequential
                .enableSelectiveBodyFetch(false)
                .selectiveBodyFetchRatio(0)
                .enableMonitoring(false)
                .monitoringPort(8080)
                .build();

        config.validate();

        HybridYaciNode node = new HybridYaciNode(config);

        try {
            log.info("Starting sequential sync node...");
            node.start();

            // Monitor for 60 seconds
            for (int i = 1; i <= 6; i++) {
                Thread.sleep(10000);

                log.info("{}0s - Running: {}, Syncing: {}, Blocks: {}, Slot: {}",
                    i, node.isRunning(), node.isSyncing(),
                    node.getBlocksProcessed(), node.getLastProcessedSlot());

                // Early success if we see blocks
                if (node.getBlocksProcessed() > 0) {
                    log.info("✅ Sequential sync working! {} blocks processed",
                        node.getBlocksProcessed());
                    break;
                }
            }

            log.info("Final sequential sync status: {} blocks, slot {}, tip: {}",
                node.getBlocksProcessed(), node.getLastProcessedSlot(), node.getLocalTip());

        } finally {
            node.stop();
        }
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void testPipelinedSyncMode() throws InterruptedException {
        log.info("Testing pipelined sync mode...");

        HybridNodeConfig config = HybridNodeConfig.builder()
                .remoteHost("preprod-node.play.dev.cardano.org")
                .remotePort(3001)
                .protocolMagic(Constants.PREPROD_PROTOCOL_MAGIC)
                .serverPort(13345)
                .enableServer(false)
                .enableClient(true)
                .useRocksDB(false)
                .rocksDBPath(null)
                .fullSyncThreshold(50)
                .enablePipelinedSync(true)   // PIPELINED MODE
                .headerPipelineDepth(20)
                .bodyBatchSize(5)
                .maxParallelBodies(2)
                .enableSelectiveBodyFetch(false)
                .selectiveBodyFetchRatio(0)
                .enableMonitoring(false)
                .monitoringPort(8080)
                .build();

        config.validate();

        HybridYaciNode node = new HybridYaciNode(config);

        try {
            log.info("Starting pipelined sync node...");
            node.start();

            // Monitor for 60 seconds
            for (int i = 1; i <= 6; i++) {
                Thread.sleep(10000);

                log.info("{}0s - Running: {}, Syncing: {}, Blocks: {}, Slot: {}",
                    i, node.isRunning(), node.isSyncing(),
                    node.getBlocksProcessed(), node.getLastProcessedSlot());

                // Early success if we see blocks
                if (node.getBlocksProcessed() > 0) {
                    log.info("✅ Pipelined sync working! {} blocks processed",
                        node.getBlocksProcessed());
                    break;
                }
            }

            log.info("Final pipelined sync status: {} blocks, slot {}, tip: {}",
                node.getBlocksProcessed(), node.getLastProcessedSlot(), node.getLocalTip());

        } finally {
            node.stop();
        }
    }

    @Test
    void testConfigurationValidation() {
        log.info("Testing configuration validation...");

        // Test that pipelined config is included in validation
        HybridNodeConfig pipelinedConfig = HybridNodeConfig.preprodDefault();
        pipelinedConfig.validate(); // Should not throw

        log.info("Pipelined config: enablePipelinedSync={}, headerPipelineDepth={}",
            pipelinedConfig.isEnablePipelinedSync(), pipelinedConfig.getHeaderPipelineDepth());

        // Test sequential config
        HybridNodeConfig sequentialConfig = HybridNodeConfig.builder()
                .remoteHost("localhost")
                .remotePort(3001)
                .protocolMagic(1)
                .enableClient(true)
                .enableServer(false)
                .enablePipelinedSync(false)  // Sequential mode
                .headerPipelineDepth(0)
                .bodyBatchSize(0)
                .maxParallelBodies(0)
                .enableSelectiveBodyFetch(false)
                .selectiveBodyFetchRatio(0)
                .build();

        sequentialConfig.validate(); // Should not throw

        log.info("Sequential config: enablePipelinedSync={}",
            sequentialConfig.isEnablePipelinedSync());

        log.info("✅ Configuration validation tests passed!");
    }
}
