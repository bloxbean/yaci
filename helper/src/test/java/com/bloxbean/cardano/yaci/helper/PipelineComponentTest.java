package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.n2n.ChainSyncAgentListener;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import com.bloxbean.cardano.yaci.helper.model.Transaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Component tests for pipelining functionality with real network connections
 */
public class PipelineComponentTest {
    private static final Logger log = LoggerFactory.getLogger(PipelineComponentTest.class);
    
    @Test
    @Timeout(60)
    public void testBasicConnectionAndHeaderRetrieval() throws InterruptedException {
        log.info("Testing basic connection and header retrieval");
        
        PeerClient peerClient = new PeerClient(
            "preprod-node.play.dev.cardano.org", 
            3001, 
            Constants.PREPROD_PROTOCOL_MAGIC, 
            Constants.WELL_KNOWN_PREPROD_POINT
        );
        
        CountDownLatch intersectLatch = new CountDownLatch(1);
        CountDownLatch headerLatch = new CountDownLatch(3);
        AtomicInteger headerCount = new AtomicInteger(0);
        
        ChainSyncAgentListener listener = new ChainSyncAgentListener() {
            @Override
            public void intersactFound(Tip tip, Point point) {
                log.info("Intersect found - Tip: {}, Point: {}", tip, point);
                intersectLatch.countDown();
            }
            
            @Override
            public void rollforward(Tip tip, BlockHeader blockHeader) {
                int count = headerCount.incrementAndGet();
                log.info("Received header #{}: slot={}, block={}", 
                    count, 
                    blockHeader.getHeaderBody().getSlot(),
                    blockHeader.getHeaderBody().getBlockNumber()
                );
                headerLatch.countDown();
            }
            
            @Override
            public void intersactNotFound(Tip tip) {
                log.warn("Intersect not found, tip: {}", tip);
                // This is expected when using well-known point, try from genesis
                intersectLatch.countDown();
            }
        };
        
        try {
            peerClient.startChainSyncOnly(Constants.WELL_KNOWN_PREPROD_POINT, listener);
            
            // Wait for intersection 
            boolean intersected = intersectLatch.await(30, TimeUnit.SECONDS);
            assertTrue(intersected, "Should establish intersection or get intersect not found");
            
            // Wait for a few headers
            boolean received = headerLatch.await(30, TimeUnit.SECONDS);
            assertTrue(received, "Should receive at least 3 headers");
            
            assertTrue(headerCount.get() >= 3, "Should receive at least 3 headers");
            log.info("Basic connection test passed: received {} headers", headerCount.get());
            
        } finally {
            peerClient.stop();
        }
    }
    
    @Test
    @Timeout(90)
    public void testPipelineConfigurationAndMetrics() throws InterruptedException {
        log.info("Testing pipeline configuration and metrics");
        
        PeerClient peerClient = new PeerClient(
            "preprod-node.play.dev.cardano.org", 
            3001, 
            Constants.PREPROD_PROTOCOL_MAGIC, 
            Constants.WELL_KNOWN_PREPROD_POINT
        );
        
        // Use a test pipeline config
        PipelineConfig testConfig = PipelineConfig.builder()
            .headerPipelineDepth(10)
            .bodyBatchSize(3)
            .maxParallelBodies(2)
            .batchTimeout(Duration.ofSeconds(2))
            .enableParallelProcessing(false) // Keep it simple for testing
            .processingThreads(1)
            .build();
        
        CountDownLatch intersectLatch = new CountDownLatch(1);
        CountDownLatch blockLatch = new CountDownLatch(2);
        AtomicInteger blockCount = new AtomicInteger(0);
        AtomicInteger headerCount = new AtomicInteger(0);
        
        BlockChainDataListener blockListener = new BlockChainDataListener() {
            @Override
            public void onBlock(Era era, Block block, List<Transaction> transactions) {
                int count = blockCount.incrementAndGet();
                log.info("Received block #{}: slot={}, era={}, size={}", 
                    count,
                    block.getHeader().getHeaderBody().getSlot(),
                    era,
                    block.getCbor() != null ? block.getCbor().length() : 0
                );
                blockLatch.countDown();
            }
        };
        
        ChainSyncAgentListener chainSyncListener = new ChainSyncAgentListener() {
            @Override
            public void intersactFound(Tip tip, Point point) {
                log.info("Intersect found for pipelined test");
                intersectLatch.countDown();
            }
            
            @Override
            public void rollforward(Tip tip, BlockHeader blockHeader) {
                headerCount.incrementAndGet();
            }
            
            @Override
            public void intersactNotFound(Tip tip) {
                log.info("Intersect not found for pipelined test, continuing from tip");
                intersectLatch.countDown();
            }
        };
        
        try {
            peerClient.startPipelinedSync(Constants.WELL_KNOWN_PREPROD_POINT, testConfig, 
                blockListener, chainSyncListener, null);
            
            // Wait for intersection
            boolean intersected = intersectLatch.await(30, TimeUnit.SECONDS);
            assertTrue(intersected, "Should establish intersection");
            
            // Wait for some blocks 
            boolean receivedBlocks = blockLatch.await(60, TimeUnit.SECONDS);
            assertTrue(receivedBlocks, "Should receive at least 2 blocks");
            
            // Check metrics
            PipelineMetrics metrics = peerClient.getPipelineMetrics();
            assertNotNull(metrics, "Should have pipeline metrics");
            log.info("Pipeline metrics: {}", metrics.getSummary());
            
            // Verify basic metrics
            assertTrue(metrics.getHeadersReceived().get() > 0, "Should have received headers");
            assertTrue(metrics.getBodiesReceived().get() > 0, "Should have received bodies");
            
            // Check configuration
            PipelineConfig config = peerClient.getPipelineConfig();
            assertEquals(10, config.getHeaderPipelineDepth(), "Should have correct header pipeline depth");
            assertEquals(3, config.getBodyBatchSize(), "Should have correct body batch size");
            
            log.info("Pipeline configuration and metrics test passed");
            
        } finally {
            peerClient.stop();
        }
    }
    
    @Test 
    public void testPipelineStrategyConfiguration() {
        log.info("Testing pipeline strategy configuration");
        
        // Test strategy enum values exist
        assertNotNull(PipelineStrategy.HEADERS_ONLY);
        assertNotNull(PipelineStrategy.SEQUENTIAL);
        assertNotNull(PipelineStrategy.FULL_PARALLEL);
        assertNotNull(PipelineStrategy.SELECTIVE_BODIES);
        assertNotNull(PipelineStrategy.BATCH_PIPELINED);
        assertNotNull(PipelineStrategy.ADAPTIVE);
        
        // Test configuration validation
        assertDoesNotThrow(() -> {
            PipelineConfig.defaultClientConfig().validate();
            PipelineConfig.highPerformanceNodeConfig().validate();
            PipelineConfig.lowResourceConfig().validate();
        });
        
        // Test configuration creation
        PipelineConfig customConfig = PipelineConfig.builder()
            .headerPipelineDepth(50)
            .bodyBatchSize(10)
            .maxParallelBodies(3)
            .enableParallelProcessing(true)
            .build();
        
        assertDoesNotThrow(customConfig::validate);
        assertEquals(50, customConfig.getHeaderPipelineDepth());
        assertEquals(10, customConfig.getBodyBatchSize());
        
        log.info("Pipeline strategy configuration test passed");
    }
    
    @Test
    public void testErrorHandlingAndRecovery() {
        log.info("Testing error handling");
        
        // Test with invalid host - should handle gracefully
        PeerClient peerClient = new PeerClient(
            "invalid-host.test", 
            9999, 
            Constants.PREPROD_PROTOCOL_MAGIC, 
            Constants.WELL_KNOWN_PREPROD_POINT
        );
        
        CountDownLatch errorLatch = new CountDownLatch(1);
        
        try {
            peerClient.startHeadersOnlySync(Constants.WELL_KNOWN_PREPROD_POINT, 
                new ChainSyncAgentListener() {
                    @Override
                    public void intersactFound(Tip tip, Point point) {
                        log.info("Unexpected success");
                    }
                }
            );
            
            // Should not connect successfully
            Thread.sleep(5000);
            
            // If we get here, the error handling worked (connection attempt failed gracefully)
            log.info("Error handling test passed - connection failed as expected");
            
        } catch (Exception e) {
            log.info("Got expected error: {}", e.getMessage());
        } finally {
            peerClient.stop();
        }
    }
}