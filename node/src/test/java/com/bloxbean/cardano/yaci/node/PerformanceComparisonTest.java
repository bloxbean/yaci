package com.bloxbean.cardano.yaci.node;

import com.bloxbean.cardano.yaci.core.common.Constants;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Disabled;

import java.util.concurrent.TimeUnit;

/**
 * Performance comparison between pipelined and sequential sync modes
 */
@Slf4j
public class PerformanceComparisonTest {
    
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    @Disabled("Enable for manual performance testing")
    void comparePipelinedVsSequentialSync() throws InterruptedException {
        log.info("=== PERFORMANCE COMPARISON: Pipelined vs Sequential Sync ===");
        
        // Test configuration
        String remoteHost = "preprod-node.play.dev.cardano.org";
        int remotePort = 3001;
        long protocolMagic = Constants.PREPROD_PROTOCOL_MAGIC;
        int testDurationSeconds = 120; // 2 minutes per test
        
        // Test 1: Sequential Sync
        log.info("\n🔄 === TEST 1: SEQUENTIAL SYNC ===");
        PerformanceResult sequentialResult = testSyncMode(
            false, // Disable pipelined sync
            remoteHost, 
            remotePort, 
            protocolMagic, 
            testDurationSeconds,
            13342
        );
        
        // Wait a bit between tests
        Thread.sleep(10000);
        
        // Test 2: Pipelined Sync
        log.info("\n🚀 === TEST 2: PIPELINED SYNC ===");
        PerformanceResult pipelinedResult = testSyncMode(
            true, // Enable pipelined sync
            remoteHost, 
            remotePort, 
            protocolMagic, 
            testDurationSeconds,
            13343
        );
        
        // Compare results
        log.info("\n📊 === PERFORMANCE COMPARISON RESULTS ===");
        compareResults(sequentialResult, pipelinedResult);
    }
    
    private PerformanceResult testSyncMode(boolean enablePipelined, String host, int port, 
                                         long magic, int durationSeconds, int serverPort) 
                                         throws InterruptedException {
        
        String mode = enablePipelined ? "PIPELINED" : "SEQUENTIAL";
        log.info("Starting {} sync test for {} seconds...", mode, durationSeconds);
        
        // Create configuration
        HybridNodeConfig config = HybridNodeConfig.builder()
                .remoteHost(host)
                .remotePort(port)
                .protocolMagic(magic)
                .serverPort(serverPort)
                .enableServer(false)  // Disable server for testing
                .enableClient(true)
                .useRocksDB(false)    // Use in-memory for testing
                .rocksDBPath(null)
                .fullSyncThreshold(50)
                .enablePipelinedSync(enablePipelined)  // Key difference
                .headerPipelineDepth(50)
                .bodyBatchSize(10)
                .maxParallelBodies(5)
                .enableSelectiveBodyFetch(false)  // Fetch all for fair comparison
                .selectiveBodyFetchRatio(0)
                .enableMonitoring(false)
                .monitoringPort(8080)
                .build();
        
        config.validate();
        
        HybridYaciNode node = new HybridYaciNode(config);
        
        long startTime = System.currentTimeMillis();
        long startBlocks = 0;
        long startSlot = 0;
        
        try {
            log.info("Starting {} node...", mode);
            node.start();
            
            // Record initial state
            Thread.sleep(2000); // Wait for startup
            startBlocks = node.getBlocksProcessed();
            startSlot = node.getLastProcessedSlot();
            
            log.info("{} sync started - initial blocks: {}, slot: {}", 
                mode, startBlocks, startSlot);
            
            // Monitor progress
            int checkIntervalSeconds = 10;
            int totalChecks = durationSeconds / checkIntervalSeconds;
            
            for (int i = 1; i <= totalChecks; i++) {
                Thread.sleep(checkIntervalSeconds * 1000);
                
                long currentBlocks = node.getBlocksProcessed();
                long currentSlot = node.getLastProcessedSlot();
                int elapsedSeconds = i * checkIntervalSeconds;
                
                long blocksPerSecond = (currentBlocks - startBlocks) / elapsedSeconds;
                long slotsPerSecond = (currentSlot - startSlot) / elapsedSeconds;
                
                log.info("{} {}s: {} blocks (+{}), slot {} (+{}), rate: {}/s blocks, {}/s slots",
                    mode, elapsedSeconds, currentBlocks, (currentBlocks - startBlocks),
                    currentSlot, (currentSlot - startSlot), blocksPerSecond, slotsPerSecond);
            }
            
            // Final measurements
            long endTime = System.currentTimeMillis();
            long finalBlocks = node.getBlocksProcessed();
            long finalSlot = node.getLastProcessedSlot();
            long totalDurationMs = endTime - startTime;
            
            PerformanceResult result = new PerformanceResult(
                mode,
                enablePipelined,
                totalDurationMs,
                finalBlocks - startBlocks,
                finalSlot - startSlot,
                node.getLocalTip()
            );
            
            log.info("{} test completed: {}", mode, result);
            return result;
            
        } finally {
            log.info("Stopping {} node...", mode);
            node.stop();
        }
    }
    
    private void compareResults(PerformanceResult sequential, PerformanceResult pipelined) {
        log.info("=== DETAILED COMPARISON ===");
        log.info("Sequential: {}", sequential);
        log.info("Pipelined:  {}", pipelined);
        
        // Calculate performance improvements
        double blockSpeedImprovement = calculateImprovement(
            sequential.getBlocksPerSecond(), 
            pipelined.getBlocksPerSecond()
        );
        
        double slotSpeedImprovement = calculateImprovement(
            sequential.getSlotsPerSecond(), 
            pipelined.getSlotsPerSecond()
        );
        
        log.info("\n🎯 === PERFORMANCE SUMMARY ===");
        log.info("Block Processing Speed:");
        log.info("  Sequential: {:.2f} blocks/second", sequential.getBlocksPerSecond());
        log.info("  Pipelined:  {:.2f} blocks/second", pipelined.getBlocksPerSecond());
        log.info("  Improvement: {:.1f}%", blockSpeedImprovement);
        
        log.info("Slot Progress Speed:");
        log.info("  Sequential: {:.2f} slots/second", sequential.getSlotsPerSecond());
        log.info("  Pipelined:  {:.2f} slots/second", pipelined.getSlotsPerSecond());
        log.info("  Improvement: {:.1f}%", slotSpeedImprovement);
        
        log.info("Total Blocks Processed:");
        log.info("  Sequential: {} blocks", sequential.getTotalBlocks());
        log.info("  Pipelined:  {} blocks", pipelined.getTotalBlocks());
        log.info("  Difference: {} blocks", pipelined.getTotalBlocks() - sequential.getTotalBlocks());
        
        // Conclusion
        if (blockSpeedImprovement > 10) {
            log.info("\n✅ CONCLUSION: Pipelined sync shows significant improvement ({:.1f}% faster)", 
                blockSpeedImprovement);
        } else if (blockSpeedImprovement > 0) {
            log.info("\n🔍 CONCLUSION: Pipelined sync shows modest improvement ({:.1f}% faster)", 
                blockSpeedImprovement);
        } else {
            log.info("\n⚠️  CONCLUSION: Sequential sync performed better (pipelined {:.1f}% slower)", 
                Math.abs(blockSpeedImprovement));
        }
    }
    
    private double calculateImprovement(double baseline, double improved) {
        if (baseline == 0) return 0;
        return ((improved - baseline) / baseline) * 100;
    }
    
    /**
     * Results container for performance measurements
     */
    private static class PerformanceResult {
        private final String mode;
        private final boolean isPipelined;
        private final long durationMs;
        private final long totalBlocks;
        private final long totalSlots;
        private final Object finalTip;
        
        public PerformanceResult(String mode, boolean isPipelined, long durationMs, 
                               long totalBlocks, long totalSlots, Object finalTip) {
            this.mode = mode;
            this.isPipelined = isPipelined;
            this.durationMs = durationMs;
            this.totalBlocks = totalBlocks;
            this.totalSlots = totalSlots;
            this.finalTip = finalTip;
        }
        
        public double getBlocksPerSecond() {
            return totalBlocks / (durationMs / 1000.0);
        }
        
        public double getSlotsPerSecond() {
            return totalSlots / (durationMs / 1000.0);
        }
        
        public long getTotalBlocks() {
            return totalBlocks;
        }
        
        @Override
        public String toString() {
            return String.format("%s: %d blocks, %d slots in %.1fs (%.2f bl/s, %.2f sl/s) - tip: %s",
                mode, totalBlocks, totalSlots, durationMs/1000.0, 
                getBlocksPerSecond(), getSlotsPerSecond(), finalTip);
        }
    }
}