package com.bloxbean.cardano.yaci.node.runtime;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.HeaderBody;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.helper.PeerClient;
import com.bloxbean.cardano.yaci.helper.model.Transaction;
import com.bloxbean.cardano.yaci.node.runtime.chain.InMemoryChainState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simplified functional tests for BodyFetchManager without complex mocking.
 * Tests the basic functionality using real InMemoryChainState and mock PeerClient.
 */
class BodyFetchManagerSimpleTest {
    
    private BodyFetchManager bodyFetchManager;
    private MockPeerClient mockPeerClient;
    private InMemoryChainState chainState;
    
    // Test configuration
    private static final int GAP_THRESHOLD = 3;
    private static final int MAX_BATCH_SIZE = 5;
    private static final long MONITORING_INTERVAL = 100; // Fast for testing
    
    @BeforeEach
    void setUp() {
        chainState = new InMemoryChainState();
        mockPeerClient = new MockPeerClient();
        
        bodyFetchManager = new BodyFetchManager(
            mockPeerClient, 
            chainState, 
            GAP_THRESHOLD, 
            MAX_BATCH_SIZE, 
            MONITORING_INTERVAL
        );
    }
    
    @AfterEach
    void tearDown() {
        if (bodyFetchManager != null && bodyFetchManager.isRunning()) {
            bodyFetchManager.stop();
        }
    }
    
    @Test
    @DisplayName("Test BodyFetchManager basic initialization")
    void testInitialization() {
        assertFalse(bodyFetchManager.isRunning(), "Initially not running");
        assertFalse(bodyFetchManager.isPaused(), "Initially not paused");
        assertEquals(0, bodyFetchManager.getCurrentGapSize(), "Initially no gap");
        
        BodyFetchManager.BodyFetchStatus status = bodyFetchManager.getStatus();
        assertFalse(status.active, "Status should show inactive");
        assertEquals(0, status.bodiesReceived, "No bodies received initially");
        assertEquals(0, status.batchesCompleted, "No batches completed initially");
    }
    
    @Test
    @DisplayName("Test start and stop functionality")
    void testStartStop() throws InterruptedException {
        // Test start
        bodyFetchManager.start();
        assertTrue(bodyFetchManager.isRunning(), "Should be running after start");
        
        BodyFetchManager.BodyFetchStatus status = bodyFetchManager.getStatus();
        assertTrue(status.active, "Status should show active");
        
        // Give the monitoring thread a moment to start
        Thread.sleep(200);
        
        // Test stop
        bodyFetchManager.stop();
        assertFalse(bodyFetchManager.isRunning(), "Should not be running after stop");
        
        // Test restart
        bodyFetchManager.start();
        assertTrue(bodyFetchManager.isRunning(), "Should be able to restart");
        bodyFetchManager.stop();
    }
    
    @Test
    @DisplayName("Test pause and resume functionality")
    void testPauseResume() {
        assertFalse(bodyFetchManager.isPaused(), "Initially not paused");
        
        bodyFetchManager.pause();
        assertTrue(bodyFetchManager.isPaused(), "Should be paused after pause()");
        
        bodyFetchManager.resume();
        assertFalse(bodyFetchManager.isPaused(), "Should not be paused after resume()");
    }
    
    @Test
    @DisplayName("Test gap calculation with real ChainState")
    void testGapCalculationRealChainState() {
        // Initially no tips - gap should be 0
        assertEquals(0, bodyFetchManager.getCurrentGapSize(), "No gap initially");
        
        // Add header tip only - should create gap
        chainState.storeBlockHeader(
            hexToBytes("1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"), 
            1000L, 
            2000L, 
            "header-data".getBytes()
        );
        
        // Manually calculate gap since we're not running the monitoring thread
        BodyFetchManager.BodyFetchStatus status = bodyFetchManager.getStatus();
        assertNull(status.lastBodySlot, "Should have no body slot yet");
        assertEquals(2000L, status.lastHeaderSlot, "Should have header slot");
        
        // Add body tip - should reduce gap
        chainState.storeBlock(
            hexToBytes("abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890"), 
            999L, 
            1990L, 
            "block-data".getBytes()
        );
        
        status = bodyFetchManager.getStatus();
        assertNotNull(status.lastHeaderSlot, "Header slot should be set");
        assertEquals(2000L, status.lastHeaderSlot.longValue(), "Header slot should remain");
        assertNotNull(status.lastBodySlot, "Body slot should be set");
        assertEquals(1990L, status.lastBodySlot.longValue(), "Body slot should be set");
        assertEquals(10L, status.currentGapSize, "Gap should be header - body = 10");
    }
    
    @Test
    @DisplayName("Test BlockChainDataListener - onBlock method")
    void testOnBlockWithRealChainState() {
        Block block = createTestBlock(1001L, 501L, "b10c1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef");
        List<Transaction> transactions = Collections.emptyList();
        
        // Initially no bodies received
        assertEquals(0, bodyFetchManager.getStatus().bodiesReceived);
        
        // Process block
        bodyFetchManager.onBlock(Era.Shelley, block, transactions);
        
        // Verify block was stored and metrics updated
        assertEquals(1, bodyFetchManager.getStatus().bodiesReceived);
        assertEquals(1, bodyFetchManager.getStatus().totalBlocksFetched);
        
        // Verify block was stored in ChainState
        ChainTip tip = chainState.getTip();
        assertNotNull(tip, "Tip should be set after storing block");
        assertEquals(1001L, tip.getSlot(), "Tip slot should match block slot");
        assertEquals(501L, tip.getBlockNumber(), "Tip block number should match");
    }
    
    @Test
    @DisplayName("Test batch lifecycle callbacks")
    void testBatchLifecycle() {
        assertEquals(0, bodyFetchManager.getStatus().batchesCompleted);
        
        // Test batch lifecycle
        bodyFetchManager.batchStarted();
        assertEquals(0, bodyFetchManager.getStatus().batchesCompleted, "batchStarted doesn't increment");
        
        bodyFetchManager.batchDone();
        assertEquals(1, bodyFetchManager.getStatus().batchesCompleted, "batchDone increments");
    }
    
    @Test
    @DisplayName("Test rollback and disconnect handling")
    void testErrorScenarios() {
        Point rollbackPoint = new Point(950L, "rollback1234567890abcdef1234567890abcdef1234567890abcdef123456789");
        
        // These should not throw and should handle gracefully
        assertDoesNotThrow(() -> bodyFetchManager.onRollback(rollbackPoint));
        assertDoesNotThrow(() -> bodyFetchManager.onDisconnect());
        assertDoesNotThrow(() -> bodyFetchManager.noBlockFound(Point.ORIGIN, rollbackPoint));
    }
    
    @Test
    @DisplayName("Test metrics reset functionality")
    void testMetricsReset() {
        // Add some test data
        Block block = createTestBlock(1001L, 501L, "ae111c1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef");
        bodyFetchManager.onBlock(Era.Shelley, block, Collections.emptyList());
        bodyFetchManager.batchDone();
        
        // Verify metrics exist
        BodyFetchManager.BodyFetchStatus status = bodyFetchManager.getStatus();
        assertEquals(1, status.bodiesReceived);
        assertEquals(1, status.batchesCompleted);
        
        // Reset metrics
        bodyFetchManager.resetMetrics();
        
        // Verify metrics were reset
        status = bodyFetchManager.getStatus();
        assertEquals(0, status.bodiesReceived);
        assertEquals(0, status.batchesCompleted);
        assertEquals(0, status.totalBlocksFetched);
    }
    
    @Test
    @DisplayName("Test status reporting with real data")
    void testStatusReporting() {
        BodyFetchManager.BodyFetchStatus status = bodyFetchManager.getStatus();
        
        // Initial state
        assertFalse(status.active);
        assertFalse(status.paused);
        assertFalse(status.batchInProgress);
        assertEquals(0, status.bodiesReceived);
        assertEquals(0, status.batchesCompleted);
        assertNull(status.lastBodySlot);
        assertNull(status.lastHeaderSlot);
        
        // Add some data and check status updates
        chainState.storeBlockHeader(
            hexToBytes("status1234567890abcdef1234567890abcdef1234567890abcdef123456789012"), 
            2000L, 
            3000L, 
            "header".getBytes()
        );
        
        status = bodyFetchManager.getStatus();
        assertNotNull(status.lastHeaderSlot, "Should have header slot");
        assertEquals(3000L, status.lastHeaderSlot.longValue(), "Should show header slot");
        assertNull(status.lastBodySlot, "Should still have no body slot");
        assertEquals(3000L, status.currentGapSize, "Gap should equal header slot");
    }
    
    @Test
    @DisplayName("Test error handling for invalid data")
    void testErrorHandling() {
        // Test with null block - should handle gracefully (early return for null blocks)
        assertDoesNotThrow(() -> bodyFetchManager.onBlock(Era.Shelley, null, Collections.emptyList()));
        
        // Status should remain unchanged
        assertEquals(0, bodyFetchManager.getStatus().bodiesReceived);
        
        // Test with null Byron blocks - should handle gracefully
        assertDoesNotThrow(() -> bodyFetchManager.onByronBlock(null));
        assertDoesNotThrow(() -> bodyFetchManager.onByronEbBlock(null));
        
        // Status should remain unchanged
        assertEquals(0, bodyFetchManager.getStatus().bodiesReceived);
        
        // Test with blocks that have missing CBOR - should throw exceptions
        Block blockWithoutCbor = createTestBlockWithoutCbor(1001L, 501L, "missingcbor123");
        assertThrows(RuntimeException.class, () -> 
            bodyFetchManager.onBlock(Era.Shelley, blockWithoutCbor, Collections.emptyList()));
    }
    
    private Block createTestBlockWithoutCbor(long slot, long blockNumber, String hash) {
        HeaderBody headerBody = HeaderBody.builder()
            .slot(slot)
            .blockNumber(blockNumber)
            .blockHash(hash)
            .build();
        
        BlockHeader header = BlockHeader.builder()
            .headerBody(headerBody)
            .build();
        
        return Block.builder()
            .header(header)
            .cbor(null) // No CBOR data
            .build();
    }
    
    @Test
    @DisplayName("Test PeerClient integration")
    void testPeerClientIntegration() {
        // Setup gap condition that should trigger fetch
        for (int i = 0; i < GAP_THRESHOLD + 2; i++) {
            long slot = 1000 + i;
            chainState.storeBlockHeader(
                hexToBytes(String.format("header%02d567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef", i)), 
                slot, 
                slot, 
                ("header-" + i).getBytes()
            );
        }
        
        // Set mockPeerClient to running
        mockPeerClient.setRunning(true);
        
        // Start body fetch manager
        bodyFetchManager.start();
        
        // Wait a bit for monitoring to potentially trigger
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Since gap > threshold and PeerClient is running, fetch might be called
        // This tests the integration without requiring complex mocking
        
        bodyFetchManager.stop();
    }
    
    // ================================================================
    // Helper Methods and Classes
    // ================================================================
    
    private Block createTestBlock(long slot, long blockNumber, String hash) {
        HeaderBody headerBody = HeaderBody.builder()
            .slot(slot)
            .blockNumber(blockNumber)
            .blockHash(hash)
            .build();
        
        BlockHeader header = BlockHeader.builder()
            .headerBody(headerBody)
            .build();
        
        return Block.builder()
            .header(header)
            .cbor("deadbeef" + "abcd1234") // Valid hex CBOR string
            .build();
    }
    
    private byte[] hexToBytes(String hex) {
        int length = hex.length();
        byte[] data = new byte[length / 2];
        for (int i = 0; i < length; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                 + Character.digit(hex.charAt(i+1), 16));
        }
        return data;
    }
    
    // Simple mock PeerClient for testing
    private static class MockPeerClient extends PeerClient {
        private boolean running = false;
        
        public MockPeerClient() {
            super("mock-host", 3001, 1, Point.ORIGIN);
        }
        
        @Override
        public boolean isRunning() {
            return running;
        }
        
        public void setRunning(boolean running) {
            this.running = running;
        }
        
        @Override
        public void fetch(Point from, Point to) {
            // Mock implementation - just log the fetch request
            System.out.println("MockPeerClient.fetch() called: from=" + from + ", to=" + to);
        }
    }
}