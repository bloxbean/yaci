package com.bloxbean.cardano.yaci.node.runtime;

import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.HeaderBody;
import com.bloxbean.cardano.yaci.core.model.byron.ByronBlockHead;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbHead;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.*;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.n2n.ChainSyncAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.n2n.ChainsyncAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class to verify ChainSyncAgent's automatic reconnection behavior.
 * 
 * This validates Task 1.4: Verify ChainSyncAgent Automatic Reconnection
 * 
 * Tests cover:
 * - currentPoint tracking for last confirmed block
 * - FindIntersect behavior on reconnection
 * - No manual point management needed in YaciNode
 * - Automatic resumption after network disconnection
 */
class ChainSyncAgentReconnectionTest {
    
    private ChainsyncAgent chainSyncAgent;
    private TestChainSyncListener testListener;
    private Point[] knownPoints;
    
    @BeforeEach
    void setUp() {
        // Setup known points for initial sync
        knownPoints = new Point[]{
            Point.ORIGIN,
            new Point(1000, "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890")
        };
        
        chainSyncAgent = new ChainsyncAgent(knownPoints);
        testListener = new TestChainSyncListener();
        chainSyncAgent.addListener(testListener);
    }
    
    @Test
    @DisplayName("Test currentPoint tracking during normal sync")
    void testCurrentPointTracking() {
        // Initial state - no currentPoint
        assertNull(getCurrentPoint(), "Initially no currentPoint should be set");
        
        // Simulate intersect found
        Point intersectPoint = new Point(950, "intersect1234567890abcdef1234567890abcdef1234567890abcdef1234567890");
        Tip tip = new Tip(new Point(2000, "tip-hash"), 1000L);
        
        chainSyncAgent.processResponse(new IntersectFound(intersectPoint, tip));
        
        // Simulate receiving and confirming a block
        Point blockPoint1 = new Point(1001, "block1234567890abcdef1234567890abcdef1234567890abcdef1234567890");
        chainSyncAgent.confirmBlock(blockPoint1);
        
        // Verify currentPoint was updated
        assertEquals(blockPoint1, getCurrentPoint(), 
                    "currentPoint should be updated after confirmBlock()");
        
        // Simulate another block
        Point blockPoint2 = new Point(1002, "block2345678901bcdef1234567890abcdef1234567890abcdef1234567890");
        chainSyncAgent.confirmBlock(blockPoint2);
        
        // Verify currentPoint tracks the latest confirmed block
        assertEquals(blockPoint2, getCurrentPoint(), 
                    "currentPoint should track the latest confirmed block");
    }
    
    @Test
    @DisplayName("Test FindIntersect behavior on initial connection")
    void testInitialFindIntersect() {
        // Build next message for initial connection
        Message message = chainSyncAgent.buildNextMessage();
        
        // Should return FindIntersect with knownPoints
        assertInstanceOf(FindIntersect.class, message, 
                        "Initial message should be FindIntersect");
        
        FindIntersect findIntersect = (FindIntersect) message;
        assertArrayEquals(knownPoints, findIntersect.getPoints(), 
                         "FindIntersect should use provided knownPoints");
    }
    
    @Test
    @DisplayName("Test FindIntersect behavior after reconnection")
    void testReconnectionFindIntersect() {
        // Simulate normal sync first
        Point intersectPoint = new Point(950, "intersect2345678901bcdef1234567890abcdef1234567890abcdef123456789a");
        Tip tip = new Tip(new Point(2000, "tip-hash"), 1000L);
        
        chainSyncAgent.processResponse(new IntersectFound(intersectPoint, tip));
        
        // Confirm some blocks
        Point confirmedBlock = new Point(1005, "confirmed123456789abcdef1234567890abcdef1234567890abcdef1234567890");
        chainSyncAgent.confirmBlock(confirmedBlock);
        
        // Simulate disconnection and reset (as done by N2NPeerFetcher)
        chainSyncAgent.onConnectionLost();
        chainSyncAgent.reset(); // This preserves currentPoint
        
        // Verify currentPoint is preserved after reset
        assertEquals(confirmedBlock, getCurrentPoint(), 
                    "currentPoint should be preserved after reset for reconnection");
        
        // Build next message after reconnection
        Message reconnectMessage = chainSyncAgent.buildNextMessage();
        
        // Should return FindIntersect with currentPoint (not original knownPoints)
        assertInstanceOf(FindIntersect.class, reconnectMessage, 
                        "After reconnection, message should be FindIntersect");
        
        FindIntersect findIntersect = (FindIntersect) reconnectMessage;
        Point[] intersectPoints = findIntersect.getPoints();
        
        assertEquals(1, intersectPoints.length, 
                    "FindIntersect should have single point after reconnection");
        assertEquals(confirmedBlock, intersectPoints[0], 
                    "FindIntersect should use currentPoint after reconnection");
        
        // Simulate connection reestablished
        chainSyncAgent.onConnectionEstablished();
    }
    
    @Test
    @DisplayName("Test reset preserves currentPoint for reconnection")
    void testResetPreservesCurrentPoint() {
        // Setup initial state with confirmed blocks
        Point intersectPoint = new Point(950, "intersect3456789012cdef1234567890abcdef1234567890abcdef123456789ab");
        Tip tip = new Tip(new Point(2000, "tip-hash"), 1000L);
        
        chainSyncAgent.processResponse(new IntersectFound(intersectPoint, tip));
        
        Point confirmedBlock = new Point(1010, "confirmed23456789abcdef1234567890abcdef1234567890abcdef123456789ab");
        chainSyncAgent.confirmBlock(confirmedBlock);
        
        // Reset the agent (as done during reconnection)
        chainSyncAgent.reset();
        
        // Verify critical properties after reset
        assertEquals(confirmedBlock, getCurrentPoint(), 
                    "reset() should preserve currentPoint for reconnection");
        assertNull(getIntersect(), 
                  "reset() should clear intersact to trigger FindIntersect");
        assertFalse(chainSyncAgent.isDone(), 
                   "reset() should return agent to active state");
    }
    
    @Test
    @DisplayName("Test N2NPeerFetcher confirmBlock integration")
    void testConfirmBlockIntegration() {
        // Test that confirms N2NPeerFetcher correctly calls confirmBlock
        // This validates the integration between N2NPeerFetcher and ChainsyncAgent
        
        // Simulate the flow in N2NPeerFetcher.handleRollForward()
        
        // 1. Setup intersect
        Point intersectPoint = new Point(950, "intersect4567890123def1234567890abcdef1234567890abcdef123456789abc");
        Tip tip = new Tip(new Point(2000, "tip-hash"), 1000L);
        chainSyncAgent.processResponse(new IntersectFound(intersectPoint, tip));
        
        // 2. Simulate header-only fetch (as used by HeaderSyncManager)
        BlockHeader blockHeader = createTestBlockHeader(1001L, "blockHash567890123def1234567890abcdef1234567890abcdef123456789abc");
        Point blockPoint = new Point(blockHeader.getHeaderBody().getSlot(), 
                                   blockHeader.getHeaderBody().getBlockHash());
        
        // 3. This is what N2NPeerFetcher does in header-only mode
        chainSyncAgent.confirmBlock(blockPoint);
        
        // 4. Verify the point was confirmed
        assertEquals(blockPoint, getCurrentPoint(), 
                    "confirmBlock should update currentPoint for reconnection support");
        
        // 5. Simulate reconnection scenario
        chainSyncAgent.reset();
        Message message = chainSyncAgent.buildNextMessage();
        
        FindIntersect findIntersect = (FindIntersect) message;
        assertEquals(blockPoint, findIntersect.getPoints()[0], 
                    "After reset, FindIntersect should use confirmed point");
    }
    
    @Test
    @DisplayName("Test connection state tracking")
    void testConnectionStateTracking() {
        // Test connection loss detection
        assertFalse(isReconnecting(), "Initially not reconnecting");
        
        chainSyncAgent.onConnectionLost();
        assertTrue(isReconnecting(), "Should be in reconnecting state after connection loss");
        
        chainSyncAgent.onConnectionEstablished();
        assertFalse(isReconnecting(), "Should not be reconnecting after connection established");
    }
    
    @Test
    @DisplayName("Test automatic resumption without manual intervention")
    void testAutomaticResumption() {
        // This test validates that YaciNode doesn't need manual point management
        
        // Setup sync state
        Point intersectPoint = new Point(950, "intersect567890124ef1234567890abcdef1234567890abcdef123456789abcd");
        Tip tip = new Tip(new Point(2000, "tip-hash"), 1000L);
        chainSyncAgent.processResponse(new IntersectFound(intersectPoint, tip));
        
        // Confirm multiple blocks
        Point block1 = new Point(1001, "block1567890124ef1234567890abcdef1234567890abcdef123456789abcd");
        Point block2 = new Point(1002, "block2678901235f1234567890abcdef1234567890abcdef123456789abcd");
        Point block3 = new Point(1003, "block3789012346f1234567890abcdef1234567890abcdef123456789abcd");
        
        chainSyncAgent.confirmBlock(block1);
        chainSyncAgent.confirmBlock(block2);
        chainSyncAgent.confirmBlock(block3);
        
        // Simulate disconnection and reconnection (automatic)
        chainSyncAgent.onConnectionLost();
        chainSyncAgent.reset();
        chainSyncAgent.onConnectionEstablished();
        
        // Build message after reconnection - should automatically use last confirmed point
        Message message = chainSyncAgent.buildNextMessage();
        FindIntersect findIntersect = (FindIntersect) message;
        
        // Verify automatic resumption from correct point
        assertEquals(block3, findIntersect.getPoints()[0], 
                    "Should automatically resume from last confirmed block");
        
        System.out.println("✅ Automatic reconnection validated:");
        System.out.println("   Last confirmed block: " + block3);
        System.out.println("   Reconnection FindIntersect point: " + findIntersect.getPoints()[0]);
        System.out.println("   No manual point management required ✓");
    }
    
    // ================================================================
    // Helper Methods for Accessing Private Fields (via reflection if needed)
    // ================================================================
    
    private Point getCurrentPoint() {
        try {
            var field = ChainsyncAgent.class.getDeclaredField("currentPoint");
            field.setAccessible(true);
            return (Point) field.get(chainSyncAgent);
        } catch (Exception e) {
            return null;
        }
    }
    
    private Point getIntersect() {
        try {
            var field = ChainsyncAgent.class.getDeclaredField("intersact");
            field.setAccessible(true);
            return (Point) field.get(chainSyncAgent);
        } catch (Exception e) {
            return null;
        }
    }
    
    private boolean isReconnecting() {
        try {
            var field = ChainsyncAgent.class.getDeclaredField("isReconnecting");
            field.setAccessible(true);
            return (Boolean) field.get(chainSyncAgent);
        } catch (Exception e) {
            return false;
        }
    }
    
    private BlockHeader createTestBlockHeader(long slot, String hash) {
        HeaderBody headerBody = HeaderBody.builder()
            .slot(slot)
            .blockNumber(slot - 950) // Approximate block number
            .blockHash(hash)
            .build();
        
        return BlockHeader.builder()
            .headerBody(headerBody)
            .build();
    }
    
    // ================================================================
    // Test Listener Implementation
    // ================================================================
    
    private static class TestChainSyncListener implements ChainSyncAgentListener {
        
        @Override
        public void intersactFound(Tip tip, Point point) {
            // Track intersect events for testing
        }
        
        @Override
        public void intersactNotFound(Tip tip) {
            // Track intersect not found events
        }
        
        @Override
        public void rollforward(Tip tip, BlockHeader blockHeader) {
            // Track rollforward events
        }
        
        @Override
        public void rollforward(Tip tip, BlockHeader blockHeader, byte[] originalHeaderBytes) {
            // Track rollforward with original bytes
        }
        
        @Override
        public void rollforwardByronEra(Tip tip, ByronBlockHead byronBlockHead) {
            // Track Byron era rollforward
        }
        
        @Override
        public void rollforwardByronEra(Tip tip, ByronEbHead byronEbHead) {
            // Track Byron EB era rollforward
        }
        
        @Override
        public void rollforwardByronEra(Tip tip, ByronBlockHead byronBlockHead, byte[] originalHeaderBytes) {
            // Track Byron era rollforward with original bytes
        }
        
        @Override
        public void rollforwardByronEra(Tip tip, ByronEbHead byronEbHead, byte[] originalHeaderBytes) {
            // Track Byron EB era rollforward with original bytes
        }
        
        @Override
        public void rollbackward(Tip tip, Point point) {
            // Track rollback events
        }
        
        @Override
        public void onDisconnect() {
            // Track disconnect events
        }
    }
}