package com.bloxbean.cardano.yaci.node;

import com.bloxbean.cardano.yaci.core.common.Constants;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

/**
 * Quick test to check if pipelined sync gets stuck
 */
@Slf4j
public class QuickPipelineTest {
    
    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void quickPipelinedSyncTest() throws InterruptedException {
        log.info("Starting quick pipelined sync test...");
        
        // Create minimal configuration for testing
        HybridNodeConfig config = HybridNodeConfig.builder()
                .remoteHost("preprod-node.play.dev.cardano.org")
                .remotePort(3001)
                .protocolMagic(Constants.PREPROD_PROTOCOL_MAGIC)
                .serverPort(13341)
                .enableServer(false)  // Disable server for simpler testing
                .enableClient(true)
                .useRocksDB(false)    // Use in-memory for testing
                .rocksDBPath(null)
                .fullSyncThreshold(50)
                .headerPipelineDepth(10)     // Very small values for quick testing
                .bodyBatchSize(3)
                .maxParallelBodies(1)
                .enableSelectiveBodyFetch(true)
                .selectiveBodyFetchRatio(5)  // Fetch every 5th body
                .enableMonitoring(false)
                .monitoringPort(8080)
                .build();
        
        config.validate();
        
        HybridYaciNode node = new HybridYaciNode(config);
        
        try {
            log.info("Starting hybrid node with minimal config...");
            node.start();
            
            // Wait a bit for startup
            Thread.sleep(5000);
            log.info("5s - Running: {}, Syncing: {}, Blocks: {}", 
                node.isRunning(), node.isSyncing(), node.getBlocksProcessed());
                
            // Check status every 10 seconds for 2 minutes
            for (int i = 1; i <= 12; i++) {
                Thread.sleep(10000);
                
                long blocks = node.getBlocksProcessed();
                long slot = node.getLastProcessedSlot();
                boolean running = node.isRunning();
                boolean syncing = node.isSyncing();
                
                log.info("{}0s - Running: {}, Syncing: {}, Blocks: {}, Slot: {}", 
                    i, running, syncing, blocks, slot);
                
                // Early exit if we see progress
                if (blocks > 0) {
                    log.info("✅ SUCCESS: Blocks are being processed! Pipeline sync is working.");
                    break;
                }
                
                // Check for issues
                if (i >= 6 && blocks == 0 && syncing) {
                    log.warn("⚠️  WARNING: Still syncing but no blocks after 1 minute");
                }
                
                if (!running) {
                    log.error("❌ ERROR: Node stopped running unexpectedly");
                    break;
                }
                
                if (!syncing && blocks == 0) {
                    log.error("❌ ERROR: Node not syncing and no blocks processed");
                    break;
                }
            }
            
            // Final status
            log.info("=== Final Status ===");
            log.info("Running: {}", node.isRunning());
            log.info("Syncing: {}", node.isSyncing());
            log.info("Total blocks: {}", node.getBlocksProcessed());
            log.info("Last slot: {}", node.getLastProcessedSlot());
            log.info("Local tip: {}", node.getLocalTip());
            
        } catch (Exception e) {
            log.error("Exception during quick pipeline test", e);
            throw e;
        } finally {
            log.info("Stopping node...");
            node.stop();
            log.info("Quick pipeline test completed");
        }
    }
}