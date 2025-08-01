package com.bloxbean.cardano.yaci.node;

import com.bloxbean.cardano.yaci.core.common.Constants;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Disabled;

import java.util.concurrent.TimeUnit;

/**
 * Debug test for pipelined sync functionality
 */
@Slf4j
@Disabled
public class PipelinedSyncDebugTest {

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    @Disabled("Enable for manual debugging")
    void debugPipelinedSyncStuck() throws InterruptedException {
        log.info("Starting pipelined sync debug test...");

        // Create test configuration with small pipeline for debugging
        HybridNodeConfig config = HybridNodeConfig.builder()
                .remoteHost("preprod-node.play.dev.cardano.org")
                .remotePort(3001)
                .protocolMagic(Constants.PREPROD_PROTOCOL_MAGIC)
                .serverPort(13339)
                .enableServer(false)  // Disable server for simpler debugging
                .enableClient(true)
                .useRocksDB(false)    // Use in-memory for testing
                .rocksDBPath(null)
                .fullSyncThreshold(100)
                .headerPipelineDepth(20)     // Small values for easier debugging
                .bodyBatchSize(5)
                .maxParallelBodies(2)
                .enableSelectiveBodyFetch(true)
                .selectiveBodyFetchRatio(4)  // Fetch every 4th body
                .enableMonitoring(false)
                .monitoringPort(8080)
                .build();

        config.validate();

        HybridYaciNode node = new HybridYaciNode(config);

        try {
            log.info("Starting hybrid node...");
            node.start();

            // Monitor progress for first few minutes
            for (int i = 0; i < 60; i++) { // 5 minutes
                Thread.sleep(5000); // Check every 5 seconds

                log.info("=== Debug Status ({}s) ===", i * 5);
                log.info("Running: {}", node.isRunning());
                log.info("Syncing: {}", node.isSyncing());
                log.info("Blocks processed: {}", node.getBlocksProcessed());
                log.info("Last slot: {}", node.getLastProcessedSlot());
                log.info("Local tip: {}", node.getLocalTip());

                // Check if we're making progress
                if (i > 12 && node.getBlocksProcessed() == 0) { // After 1 minute, no blocks
                    log.warn("⚠️  No blocks processed after 1 minute - possible stuck!");
                }

                if (i > 24 && node.getBlocksProcessed() < 10) { // After 2 minutes, very few blocks
                    log.warn("⚠️  Very slow progress after 2 minutes - possible performance issue!");
                }
            }

            // Final status
            log.info("=== Final Debug Status ===");
            log.info("Total blocks processed: {}", node.getBlocksProcessed());
            log.info("Final slot: {}", node.getLastProcessedSlot());
            log.info("Final tip: {}", node.getLocalTip());

        } catch (Exception e) {
            log.error("Error during pipelined sync debug test", e);
            throw e;
        } finally {
            log.info("Stopping hybrid node...");
            node.stop();
            log.info("Debug test completed");
        }
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void testPipelineConfigurationValidation() {
        log.info("Testing pipeline configuration validation...");

        // Test valid configuration
        HybridNodeConfig validConfig = HybridNodeConfig.preprodDefault();
        validConfig.validate(); // Should not throw

        log.info("Pipeline config validation:");
        log.info("  Header pipeline depth: {}", validConfig.getHeaderPipelineDepth());
        log.info("  Body batch size: {}", validConfig.getBodyBatchSize());
        log.info("  Max parallel bodies: {}", validConfig.getMaxParallelBodies());
        log.info("  Enable selective body fetch: {}", validConfig.isEnableSelectiveBodyFetch());
        log.info("  Selective body fetch ratio: {}", validConfig.getSelectiveBodyFetchRatio());

        // Test invalid configurations
        try {
            HybridNodeConfig invalidConfig = HybridNodeConfig.builder()
                    .remoteHost("test")
                    .remotePort(3001)
                    .protocolMagic(1)
                    .enableClient(true)
                    .enableServer(false)
                    .headerPipelineDepth(-1) // Invalid
                    .build();
            invalidConfig.validate();
            throw new AssertionError("Should have thrown exception for negative header pipeline depth");
        } catch (IllegalArgumentException e) {
            log.info("✅ Correctly caught invalid header pipeline depth: {}", e.getMessage());
        }

        try {
            HybridNodeConfig invalidConfig = HybridNodeConfig.builder()
                    .remoteHost("test")
                    .remotePort(3001)
                    .protocolMagic(1)
                    .enableClient(true)
                    .enableServer(false)
                    .headerPipelineDepth(10)
                    .bodyBatchSize(0) // Invalid
                    .build();
            invalidConfig.validate();
            throw new AssertionError("Should have thrown exception for zero body batch size");
        } catch (IllegalArgumentException e) {
            log.info("✅ Correctly caught invalid body batch size: {}", e.getMessage());
        }

        log.info("Pipeline configuration validation test passed!");
    }

    @Test
    void testBasicNodeCreation() {
        log.info("Testing basic node creation without starting...");

        HybridNodeConfig config = HybridNodeConfig.testConfig(
            "preprod-node.play.dev.cardano.org",
            3001,
            Constants.PREPROD_PROTOCOL_MAGIC,
            13340,
            false
        );

        config.validate();

        HybridYaciNode node = new HybridYaciNode(config);

        // Test initial state
        log.info("Initial state:");
        log.info("  Running: {}", node.isRunning());
        log.info("  Syncing: {}", node.isSyncing());
        log.info("  Server running: {}", node.isServerRunning());
        log.info("  Blocks processed: {}", node.getBlocksProcessed());
        log.info("  Configuration: {}", node.getConfig());

        log.info("Basic node creation test passed!");
    }
}
