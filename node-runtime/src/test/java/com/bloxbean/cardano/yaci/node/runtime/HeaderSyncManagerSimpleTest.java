package com.bloxbean.cardano.yaci.node.runtime;

import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.HeaderBody;
import com.bloxbean.cardano.yaci.core.model.byron.ByronBlockHead;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbHead;
import com.bloxbean.cardano.yaci.core.model.byron.ByronBlockCons;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbBlockCons;
import com.bloxbean.cardano.yaci.core.model.Epoch;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.helper.PeerClient;
import com.bloxbean.cardano.yaci.node.runtime.chain.InMemoryChainState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simplified functional tests for HeaderSyncManager without complex mocking.
 * Tests the basic functionality using real InMemoryChainState.
 */
class HeaderSyncManagerSimpleTest {

    private InMemoryChainState chainState;
    private HeaderSyncManager headerSyncManager;
    private PeerClient mockPeerClient;
    
    @BeforeEach
    void setUp() {
        chainState = new InMemoryChainState();
        mockPeerClient = new MockPeerClient(); // Simple mock
        headerSyncManager = new HeaderSyncManager(mockPeerClient, chainState);
    }
    
    @Test
    void testHeaderSyncManager_Initialization() {
        // Test basic initialization
        assertNotNull(headerSyncManager);
        assertEquals(0, headerSyncManager.getHeadersReceived());
        
        // Test initial metrics
        HeaderSyncManager.HeaderMetrics metrics = headerSyncManager.getHeaderMetrics();
        assertEquals(0, metrics.totalHeaders);
        assertEquals(0, metrics.shelleyHeaders);
        assertEquals(0, metrics.byronHeaders);
        assertEquals(0, metrics.byronEbHeaders);
    }
    
    @Test
    void testRollforward_ShelleyHeader_WithRealChainState() {
        // Create a real header (simplified)
        BlockHeader header = createSimpleShelleyHeader(1000L, 500L, "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890");
        Tip tip = new Tip(new Point(1000, "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890"), 500L);
        byte[] headerBytes = "mock-header-data".getBytes();
        
        // Execute
        headerSyncManager.rollforward(tip, header, headerBytes);
        
        // Verify metrics
        assertEquals(1, headerSyncManager.getHeadersReceived());
        assertEquals(1, headerSyncManager.getHeaderMetrics().shelleyHeaders);
        
        // Verify storage in ChainState
        ChainTip headerTip = chainState.getHeaderTip();
        assertNotNull(headerTip);
        assertEquals(1000L, headerTip.getSlot());
        assertEquals(500L, headerTip.getBlockNumber());
    }
    
    @Test
    void testRollforward_ByronHeader_WithRealChainState() {
        // Create a real Byron header (simplified)
        ByronBlockHead byronHeader = createSimpleByronHeader(999L, 499L, "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef");
        Tip tip = new Tip(new Point(999, "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"), 499L);
        byte[] headerBytes = "mock-byron-header-data".getBytes();
        
        // Execute
        headerSyncManager.rollforwardByronEra(tip, byronHeader, headerBytes);
        
        // Verify metrics
        assertEquals(1, headerSyncManager.getHeadersReceived());
        assertEquals(1, headerSyncManager.getHeaderMetrics().byronHeaders);
        
        // Verify storage in ChainState
        ChainTip headerTip = chainState.getHeaderTip();
        assertNotNull(headerTip);
        assertEquals(999L, headerTip.getSlot());
        assertEquals(499L, headerTip.getBlockNumber());
    }
    
    @Test
    void testRollforward_ByronEbHeader_WithRealChainState() {
        // Create a real Byron EB header (simplified)
        ByronEbHead byronEbHeader = createSimpleByronEbHeader(21600L, 1L, "fedcba0987654321fedcba0987654321fedcba0987654321fedcba0987654321");
        Tip tip = new Tip(new Point(21600, "fedcba0987654321fedcba0987654321fedcba0987654321fedcba0987654321"), 1L);
        byte[] headerBytes = "mock-byron-eb-header-data".getBytes();
        
        // Execute
        headerSyncManager.rollforwardByronEra(tip, byronEbHeader, headerBytes);
        
        // Verify metrics
        assertEquals(1, headerSyncManager.getHeadersReceived());
        assertEquals(1, headerSyncManager.getHeaderMetrics().byronEbHeaders);
        
        // Verify storage in ChainState
        ChainTip headerTip = chainState.getHeaderTip();
        assertNotNull(headerTip);
        assertEquals(21600L, headerTip.getSlot());
        assertEquals(1L, headerTip.getBlockNumber());
    }
    
    @Test
    void testMultipleHeaderTypes() {
        // Test multiple headers from different eras
        byte[] headerBytes = "header-data".getBytes();
        
        // Add Shelley header
        headerSyncManager.rollforward(
            new Tip(new Point(1000, "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234"), 500L),
            createSimpleShelleyHeader(1000L, 500L, "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234"),
            headerBytes
        );
        
        // Add Byron header
        headerSyncManager.rollforwardByronEra(
            new Tip(new Point(999, "1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd"), 499L),
            createSimpleByronHeader(999L, 499L, "1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd"),
            headerBytes
        );
        
        // Add Byron EB header
        headerSyncManager.rollforwardByronEra(
            new Tip(new Point(21600, "dcba4321dcba4321dcba4321dcba4321dcba4321dcba4321dcba4321dcba4321"), 1L),
            createSimpleByronEbHeader(21600L, 1L, "dcba4321dcba4321dcba4321dcba4321dcba4321dcba4321dcba4321dcba4321"),
            headerBytes
        );
        
        // Verify total metrics
        HeaderSyncManager.HeaderMetrics metrics = headerSyncManager.getHeaderMetrics();
        assertEquals(3, metrics.totalHeaders);
        assertEquals(1, metrics.shelleyHeaders);
        assertEquals(1, metrics.byronHeaders);
        assertEquals(1, metrics.byronEbHeaders);
        
        // Latest header should be in ChainState
        ChainTip headerTip = chainState.getHeaderTip();
        assertNotNull(headerTip);
        // The last header stored should be the current tip
    }
    
    @Test
    void testInvalidInputHandling() {
        // Test null block header
        assertDoesNotThrow(() -> {
            headerSyncManager.rollforward(
                new Tip(new Point(1000, "tip"), 500L),
                null,
                "data".getBytes()
            );
        });
        assertEquals(0, headerSyncManager.getHeadersReceived()); // Should not increment
        
        // Test null header bytes
        assertDoesNotThrow(() -> {
            headerSyncManager.rollforward(
                new Tip(new Point(1000, "cccc3333cccc3333cccc3333cccc3333cccc3333cccc3333cccc3333cccc3333"), 500L),
                createSimpleShelleyHeader(1000L, 500L, "cccc3333cccc3333cccc3333cccc3333cccc3333cccc3333cccc3333cccc3333"),
                null
            );
        });
        assertEquals(0, headerSyncManager.getHeadersReceived()); // Should not increment
        
        // Test empty header bytes
        assertDoesNotThrow(() -> {
            headerSyncManager.rollforward(
                new Tip(new Point(1000, "dddd4444dddd4444dddd4444dddd4444dddd4444dddd4444dddd4444dddd4444"), 500L),
                createSimpleShelleyHeader(1000L, 500L, "dddd4444dddd4444dddd4444dddd4444dddd4444dddd4444dddd4444dddd4444"),
                new byte[0]
            );
        });
        assertEquals(0, headerSyncManager.getHeadersReceived()); // Should not increment
    }
    
    @Test
    void testControlFlowMethods() {
        // Test methods that don't modify state
        assertDoesNotThrow(() -> {
            headerSyncManager.intersactFound(new Tip(new Point(1000, "tip"), 500L), new Point(900, "intersect"));
            headerSyncManager.intersactNotFound(new Tip(new Point(1000, "tip"), 500L));
            headerSyncManager.rollbackward(new Tip(new Point(800, "tip"), 400L), new Point(700, "rollback"));
            headerSyncManager.onDisconnect();
        });
    }
    
    @Test
    void testMetricsReset() {
        // Add some headers
        headerSyncManager.rollforward(
            new Tip(new Point(1000, "aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111"), 500L),
            createSimpleShelleyHeader(1000L, 500L, "aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111"),
            "data".getBytes()
        );
        assertEquals(1, headerSyncManager.getHeadersReceived());
        
        // Reset metrics
        headerSyncManager.resetMetrics();
        
        // Verify reset
        assertEquals(0, headerSyncManager.getHeadersReceived());
        HeaderSyncManager.HeaderMetrics metrics = headerSyncManager.getHeaderMetrics();
        assertEquals(0, metrics.totalHeaders);
        assertEquals(0, metrics.shelleyHeaders);
        assertEquals(0, metrics.byronHeaders);
        assertEquals(0, metrics.byronEbHeaders);
    }
    
    @Test
    void testGetStatus() {
        // Test with no headers
        HeaderSyncManager.HeaderSyncStatus status = headerSyncManager.getStatus();
        assertTrue(status.active); // MockPeerClient returns true
        assertEquals(0, status.headersReceived);
        assertNull(status.lastHeaderSlot);
        assertNull(status.lastHeaderBlockNumber);
        
        // Add a header
        headerSyncManager.rollforward(
            new Tip(new Point(1000, "bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222"), 500L),
            createSimpleShelleyHeader(1000L, 500L, "bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222"),
            "data".getBytes()
        );
        
        // Test with headers
        status = headerSyncManager.getStatus();
        assertTrue(status.active);
        assertEquals(1, status.headersReceived);
        assertEquals(1000L, status.lastHeaderSlot);
        assertEquals(500L, status.lastHeaderBlockNumber);
        assertNotNull(status.currentHeaderTip);
    }
    
    // ================================================================
    // Helper Methods for Creating Real Objects (No Mocking)
    // ================================================================
    
    private BlockHeader createSimpleShelleyHeader(long slot, long blockNumber, String hash) {
        HeaderBody headerBody = HeaderBody.builder()
            .slot(slot)
            .blockNumber(blockNumber)
            .blockHash(hash)
            .build();
        
        return BlockHeader.builder()
            .headerBody(headerBody)
            .build();
    }
    
    private ByronBlockHead createSimpleByronHeader(long absoluteSlot, long blockNumber, String hash) {
        Epoch slotId = Epoch.builder()
            .epoch(absoluteSlot / 21600)
            .slot(absoluteSlot % 21600)
            .build();
        
        ByronBlockCons consensusData = ByronBlockCons.builder()
            .slotId(slotId)
            .difficulty(BigInteger.valueOf(blockNumber))
            .build();
        
        return ByronBlockHead.builder()
            .consensusData(consensusData)
            .blockHash(hash)
            .build();
    }
    
    private ByronEbHead createSimpleByronEbHeader(long absoluteSlot, long blockNumber, String hash) {
        ByronEbBlockCons consensusData = ByronEbBlockCons.builder()
            .epoch(absoluteSlot / 21600)
            .difficulty(BigInteger.valueOf(blockNumber))
            .build();
        
        return ByronEbHead.builder()
            .consensusData(consensusData)
            .blockHash(hash)
            .build();
    }
    
    // Simple mock PeerClient for testing
    private static class MockPeerClient extends PeerClient {
        public MockPeerClient() {
            super("mock-host", 3001, 1, null);
        }
        
        @Override
        public boolean isRunning() {
            return true;
        }
    }
}