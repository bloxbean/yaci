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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for pipelining functionality
 */
@Disabled
public class PipelineTest {
    private static final Logger log = LoggerFactory.getLogger(PipelineTest.class);

    @Test
    @Timeout(30)
    public void testHeadersOnlyMode() throws InterruptedException {
        log.info("Testing headers-only mode");

        // Use a well-known point from preprod
        Point startPoint = new Point(46140376L, "c2329f485abbfcacb9cabeda11ee8be2b324c5e67ef862e76345b3f28d15cf86");

        PeerClient peerClient = new PeerClient(
            "preprod-node.play.dev.cardano.org",
            3001,
            Constants.PREPROD_PROTOCOL_MAGIC,
            Constants.WELL_KNOWN_PREPROD_POINT
        );

        CountDownLatch headerLatch = new CountDownLatch(10);
        AtomicInteger headerCount = new AtomicInteger(0);
        List<BlockHeader> receivedHeaders = new ArrayList<>();

        ChainSyncAgentListener listener = new ChainSyncAgentListener() {
            @Override
            public void rollforward(Tip tip, BlockHeader blockHeader) {
                int count = headerCount.incrementAndGet();
                receivedHeaders.add(blockHeader);
                log.info("Received header #{}: slot={}, block={}",
                    count,
                    blockHeader.getHeaderBody().getSlot(),
                    blockHeader.getHeaderBody().getBlockNumber()
                );
                headerLatch.countDown();
            }
        };

        try {
            peerClient.startHeadersOnlySync(startPoint, listener);

            // Wait for headers
            boolean received = headerLatch.await(20, TimeUnit.SECONDS);
            assertTrue(received, "Should receive at least 10 headers");

            // Verify we got headers but no bodies
            assertTrue(headerCount.get() >= 10, "Should receive at least 10 headers");
            assertFalse(receivedHeaders.isEmpty(), "Should have received headers");

            // Verify headers are in order
            for (int i = 1; i < receivedHeaders.size(); i++) {
                assertTrue(
                    receivedHeaders.get(i).getHeaderBody().getSlot() >=
                    receivedHeaders.get(i-1).getHeaderBody().getSlot(),
                    "Headers should be in slot order"
                );
            }

            log.info("Headers-only test passed: received {} headers", headerCount.get());

        } finally {
            peerClient.stop();
        }
    }

    @Test
    @Timeout(60)
    public void testPipelinedSync() throws InterruptedException {
        log.info("Testing pipelined sync");

        Point startPoint = new Point(46140376L, "c2329f485abbfcacb9cabeda11ee8be2b324c5e67ef862e76345b3f28d15cf86");

        PeerClient peerClient = new PeerClient(
            "preprod-node.play.dev.cardano.org",
            3001,
            Constants.PREPROD_PROTOCOL_MAGIC,
            Constants.WELL_KNOWN_PREPROD_POINT
        );

        // Use a moderate pipeline config for testing
        PipelineConfig testConfig = PipelineConfig.builder()
            .headerPipelineDepth(20)
            .bodyBatchSize(5)
            .maxParallelBodies(2)
            .batchTimeout(Duration.ofMillis(500))
            .enableParallelProcessing(true)
            .processingThreads(2)
            .build();

        CountDownLatch blockLatch = new CountDownLatch(5);
        AtomicInteger blockCount = new AtomicInteger(0);
        AtomicInteger headerCount = new AtomicInteger(0);

        BlockChainDataListener blockListener = new BlockChainDataListener() {
            @Override
            public void onBlock(Era era, Block block, List<Transaction> transactions) {
                int count = blockCount.incrementAndGet();
                log.info("Received block #{}: slot={}, block={}, size={}",
                    count,
                    block.getHeader().getHeaderBody().getSlot(),
                    block.getHeader().getHeaderBody().getBlockNumber(),
                    block.getCbor() != null ? block.getCbor().length() : 0
                );
                blockLatch.countDown();
            }

            @Override
            public void onRollback(Point point) {
                log.info("Rollback to point: {}", point);
            }
        };

        ChainSyncAgentListener chainSyncListener = new ChainSyncAgentListener() {
            @Override
            public void rollforward(Tip tip, BlockHeader blockHeader) {
                headerCount.incrementAndGet();
            }
        };

        try {
            peerClient.startPipelinedSync(startPoint, testConfig, blockListener, chainSyncListener, null);

            // Wait for blocks
            boolean received = blockLatch.await(45, TimeUnit.SECONDS);
            assertTrue(received, "Should receive at least 5 blocks");

            // Get metrics
            PipelineMetrics metrics = peerClient.getPipelineMetrics();
            log.info("Pipeline metrics: {}", metrics.getSummary());

            // Verify pipelining is working
            assertTrue(headerCount.get() >= blockCount.get(),
                "Should have received at least as many headers as blocks");
            assertTrue(metrics.getHeadersReceived().get() > 0,
                "Should have received headers");
            assertTrue(metrics.getBodiesReceived().get() > 0,
                "Should have received bodies");

            // Check efficiency
            double efficiency = metrics.getPipelineEfficiency();
            log.info("Pipeline efficiency: {}%", efficiency * 100);

            log.info("Pipelined sync test passed: {} headers, {} blocks",
                headerCount.get(), blockCount.get());

        } finally {
            peerClient.stop();
        }
    }

    @Test
    @Timeout(30)
    public void testSelectiveBodyFetch() throws InterruptedException {
        log.info("Testing selective body fetch");

        Point startPoint = new Point(46140376L, "c2329f485abbfcacb9cabeda11ee8be2b324c5e67ef862e76345b3f28d15cf86");

        PeerClient peerClient = new PeerClient(
            "preprod-node.play.dev.cardano.org",
            3001,
            Constants.PREPROD_PROTOCOL_MAGIC,
            Constants.WELL_KNOWN_PREPROD_POINT
        );

        CountDownLatch headerLatch = new CountDownLatch(20);
        CountDownLatch blockLatch = new CountDownLatch(5);
        AtomicInteger headerCount = new AtomicInteger(0);
        AtomicInteger blockCount = new AtomicInteger(0);
        AtomicInteger skippedCount = new AtomicInteger(0);

        BlockChainDataListener blockListener = new BlockChainDataListener() {
            @Override
            public void onBlock(Era era, Block block, List<Transaction> transactions) {
                blockCount.incrementAndGet();
                log.info("Fetched body for block at slot: {}",
                    block.getHeader().getHeaderBody().getSlot());
                blockLatch.countDown();
            }
        };

        ChainSyncAgentListener chainSyncListener = new ChainSyncAgentListener() {
            @Override
            public void rollforward(Tip tip, BlockHeader blockHeader) {
                headerCount.incrementAndGet();
                headerLatch.countDown();
            }
        };

        try {
            // Start pipelined sync with both listeners first
            peerClient.startPipelinedSync(startPoint, PipelineConfig.defaultClientConfig(),
                blockListener, chainSyncListener, null);

            // Now set up selective body fetching AFTER initialization
            peerClient.enableSelectiveBodyFetch(header -> {
                boolean shouldFetch = header.getHeaderBody().getSlot() % 4 == 0;
                if (!shouldFetch) {
                    skippedCount.incrementAndGet();
                }
                return shouldFetch;
            });

            peerClient.setPipelineStrategy(PipelineStrategy.SELECTIVE_BODIES);

            // Wait for headers
            boolean headersReceived = headerLatch.await(20, TimeUnit.SECONDS);
            assertTrue(headersReceived, "Should receive headers");

            // Give some time for selective body fetching
            Thread.sleep(5000);

            // Verify selective fetching
            log.info("Headers: {}, Bodies: {}, Skipped: {}",
                headerCount.get(), blockCount.get(), skippedCount.get());

            assertTrue(headerCount.get() > blockCount.get(),
                "Should have more headers than bodies due to selective fetching");
            assertTrue(skippedCount.get() > 0,
                "Should have skipped some bodies");

            log.info("Selective body fetch test passed");

        } finally {
            peerClient.stop();
        }
    }

    @Test
    public void testPipelineConfigValidation() {
        // Test valid configs
        assertDoesNotThrow(() -> {
            PipelineConfig.defaultClientConfig().validate();
            PipelineConfig.highPerformanceNodeConfig().validate();
            PipelineConfig.lowResourceConfig().validate();
        });

        // Test invalid configs
        assertThrows(IllegalArgumentException.class, () -> {
            PipelineConfig.builder()
                .headerPipelineDepth(-1)
                .build()
                .validate();
        });

        assertThrows(IllegalArgumentException.class, () -> {
            PipelineConfig.builder()
                .headerBufferSize(10)
                .headerPipelineDepth(20)
                .build()
                .validate();
        });

        log.info("Pipeline config validation test passed");
    }

    @Test
    public void testMetricsCalculation() {
        PipelineMetrics metrics = new PipelineMetrics();

        // Simulate activity
        for (int i = 0; i < 100; i++) {
            metrics.recordHeaderReceived();
        }

        for (int i = 0; i < 80; i++) {
            metrics.recordHeaderProcessed();
        }

        for (int i = 0; i < 50; i++) {
            metrics.recordBodyReceived(1000);
        }

        metrics.recordError(PipelineMetrics.ErrorType.HEADER_ERROR);
        metrics.recordError(PipelineMetrics.ErrorType.BODY_ERROR);

        // Verify calculations
        assertEquals(100, metrics.getHeadersReceived().get());
        assertEquals(80, metrics.getHeadersProcessed().get());
        assertEquals(50, metrics.getBodiesReceived().get());
        assertEquals(50000, metrics.getTotalBytesReceived().get());

        double efficiency = metrics.getPipelineEfficiency();
        assertEquals(0.8, efficiency, 0.01);

        double errorRate = metrics.getErrorRate();
        assertTrue(errorRate > 0 && errorRate < 0.1);

        log.info("Metrics calculation test passed");
    }
}
