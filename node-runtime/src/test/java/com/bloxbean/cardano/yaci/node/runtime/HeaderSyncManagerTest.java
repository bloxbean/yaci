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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for HeaderSyncManager
 *
 * Tests verify:
 * 1. Proper implementation of ChainSyncAgentListener interface
 * 2. Header storage via ChainState for all era types
 * 3. Metrics tracking and progress logging
 * 4. Error handling for invalid inputs
 * 5. Status reporting functionality
 */
class HeaderSyncManagerTest {

    private MockPeerClient peerClient;
    private InMemoryChainState chainState;

    private HeaderSyncManager headerSyncManager;

    @BeforeEach
    void setUp() {
        chainState = new InMemoryChainState();
        peerClient = new MockPeerClient();
        peerClient.setRunning(true);
        headerSyncManager = new HeaderSyncManager(peerClient, chainState);
    }

    // ================================================================
    // Shelley+ Era Header Tests
    // ================================================================

    @Test
    void testRollforward_ShelleyHeader_Success() {
        // Arrange
        Tip tip = new Tip(new Point(1000, "tip-hash"), 500L);
        BlockHeader blockHeader = createMockShelleyHeader(1000L, 500L, "block-hash-500");
        byte[] originalHeaderBytes = new byte[]{1, 2, 3, 4, 5}; // Mock CBOR data

        // Act
        headerSyncManager.rollforward(tip, blockHeader, originalHeaderBytes);

        // Assert
        verify(chainState).storeBlockHeader(any(byte[].class), eq(500L), eq(1000L), eq(originalHeaderBytes));
        assertEquals(1, headerSyncManager.getHeadersReceived());
        assertEquals(1, headerSyncManager.getHeaderMetrics().shelleyHeaders);
    }

    @Test
    void testRollforward_ShelleyHeader_NullBlockHeader() {
        // Arrange
        Tip tip = new Tip(new Point(1000, "tip-hash"), 500L);
        byte[] originalHeaderBytes = new byte[]{1, 2, 3, 4, 5};

        // Act & Assert - should not throw exception, should log warning
        assertDoesNotThrow(() -> headerSyncManager.rollforward(tip, null, originalHeaderBytes));
        verify(chainState, never()).storeBlockHeader(any(), any(), any(), any());
        assertEquals(0, headerSyncManager.getHeadersReceived());
    }

    @Test
    void testRollforward_ShelleyHeader_NullOriginalHeaderBytes() {
        // Arrange
        Tip tip = new Tip(new Point(1000, "tip-hash"), 500L);
        BlockHeader blockHeader = createMockShelleyHeader(1000L, 500L, "block-hash-500");

        // Act & Assert - should not throw exception, should log warning
        assertDoesNotThrow(() -> headerSyncManager.rollforward(tip, blockHeader, null));
        verify(chainState, never()).storeBlockHeader(any(), any(), any(), any());
        assertEquals(0, headerSyncManager.getHeadersReceived());
    }

    @Test
    void testRollforward_ShelleyHeader_EmptyOriginalHeaderBytes() {
        // Arrange
        Tip tip = new Tip(new Point(1000, "tip-hash"), 500L);
        BlockHeader blockHeader = createMockShelleyHeader(1000L, 500L, "block-hash-500");
        byte[] emptyBytes = new byte[0];

        // Act & Assert - should not throw exception, should log warning
        assertDoesNotThrow(() -> headerSyncManager.rollforward(tip, blockHeader, emptyBytes));
        verify(chainState, never()).storeBlockHeader(any(), any(), any(), any());
        assertEquals(0, headerSyncManager.getHeadersReceived());
    }

    @Test
    void testRollforward_ShelleyHeader_StorageException() {
        // Arrange
        Tip tip = new Tip(new Point(1000, "tip-hash"), 500L);
        BlockHeader blockHeader = createMockShelleyHeader(1000L, 500L, "block-hash-500");
        byte[] originalHeaderBytes = new byte[]{1, 2, 3, 4, 5};

        doThrow(new RuntimeException("Storage error")).when(chainState)
            .storeBlockHeader(any(byte[].class), any(Long.class), any(Long.class), any(byte[].class));

        // Act & Assert
        assertThrows(RuntimeException.class, () ->
            headerSyncManager.rollforward(tip, blockHeader, originalHeaderBytes));
    }

    // ================================================================
    // Byron Era Header Tests
    // ================================================================

    @Test
    void testRollforwardByronEra_MainBlockHeader_Success() {
        // Arrange
        Tip tip = new Tip(new Point(1000, "tip-hash"), 500L);
        ByronBlockHead byronHead = createMockByronMainBlockHead(1000L, 500L, "byron-hash-500");
        byte[] originalHeaderBytes = new byte[]{1, 2, 3, 4, 5};

        // Act
        headerSyncManager.rollforwardByronEra(tip, byronHead, originalHeaderBytes);

        // Assert
        verify(chainState).storeBlockHeader(any(byte[].class), eq(500L), eq(1000L), eq(originalHeaderBytes));
        assertEquals(1, headerSyncManager.getHeadersReceived());
        assertEquals(1, headerSyncManager.getHeaderMetrics().byronHeaders);
    }

    @Test
    void testRollforwardByronEra_EbBlockHeader_Success() {
        // Arrange
        Tip tip = new Tip(new Point(1000, "tip-hash"), 500L);
        ByronEbHead byronEbHead = createMockByronEbBlockHead(1000L, 500L, "byron-eb-hash-500");
        byte[] originalHeaderBytes = new byte[]{1, 2, 3, 4, 5};

        // Act
        headerSyncManager.rollforwardByronEra(tip, byronEbHead, originalHeaderBytes);

        // Assert
        verify(chainState).storeBlockHeader(any(byte[].class), eq(500L), eq(1000L), eq(originalHeaderBytes));
        assertEquals(1, headerSyncManager.getHeadersReceived());
        assertEquals(1, headerSyncManager.getHeaderMetrics().byronEbHeaders);
    }

    // ================================================================
    // Control Flow Method Tests
    // ================================================================

    @Test
    void testIntersactFound() {
        // Arrange
        Tip tip = new Tip(new Point(1000, "tip-hash"), 500L);
        Point point = new Point(900, "intersect-hash");

        // Act & Assert - should not throw exception, should log
        assertDoesNotThrow(() -> headerSyncManager.intersactFound(tip, point));
    }

    @Test
    void testIntersactNotFound() {
        // Arrange
        Tip tip = new Tip(new Point(1000, "tip-hash"), 500L);

        // Act & Assert - should not throw exception, should log warning
        assertDoesNotThrow(() -> headerSyncManager.intersactNotFound(tip));
    }

    @Test
    void testRollbackward() {
        // Arrange
        Tip tip = new Tip(new Point(800, "rollback-tip-hash"), 400L);
        Point toPoint = new Point(700, "rollback-point-hash");

        // Act & Assert - should not throw exception, should log
        assertDoesNotThrow(() -> headerSyncManager.rollbackward(tip, toPoint));
    }

    @Test
    void testOnDisconnect() {
        // Act & Assert - should not throw exception, should log
        assertDoesNotThrow(() -> headerSyncManager.onDisconnect());
    }

    // ================================================================
    // Metrics and Status Tests
    // ================================================================

    @Test
    void testMetricsTracking_MultipleEras() {
        // Arrange
        Tip tip = new Tip(new Point(1000, "tip-hash"), 500L);
        byte[] headerBytes = new byte[]{1, 2, 3, 4, 5};

        // Act - Add headers from different eras
        headerSyncManager.rollforward(tip, createMockShelleyHeader(1000L, 500L, "shelley-1"), headerBytes);
        headerSyncManager.rollforward(tip, createMockShelleyHeader(1001L, 501L, "shelley-2"), headerBytes);
        headerSyncManager.rollforwardByronEra(tip, createMockByronMainBlockHead(999L, 499L, "byron-1"), headerBytes);
        headerSyncManager.rollforwardByronEra(tip, createMockByronEbBlockHead(998L, 498L, "byron-eb-1"), headerBytes);

        // Assert
        HeaderSyncManager.HeaderMetrics metrics = headerSyncManager.getHeaderMetrics();
        assertEquals(4, metrics.totalHeaders);
        assertEquals(2, metrics.shelleyHeaders);
        assertEquals(1, metrics.byronHeaders);
        assertEquals(1, metrics.byronEbHeaders);
    }

    @Test
    void testGetStatus_ActiveConnection() {
        // Arrange
        ChainTip headerTip = new ChainTip(1000L, new byte[]{1, 2}, 500L);
        when(chainState.getHeaderTip()).thenReturn(headerTip);
        when(peerClient.isRunning()).thenReturn(true);

        // Add some headers
        byte[] headerBytes = new byte[]{1, 2, 3, 4, 5};
        headerSyncManager.rollforward(new Tip(new Point(1000, "tip"), 500L),
                                     createMockShelleyHeader(1000L, 500L, "block"), headerBytes);

        // Act
        HeaderSyncManager.HeaderSyncStatus status = headerSyncManager.getStatus();

        // Assert
        assertTrue(status.active);
        assertEquals(1, status.headersReceived);
        assertEquals(1000L, status.lastHeaderSlot);
        assertEquals(500L, status.lastHeaderBlockNumber);
        assertNotNull(status.currentHeaderTip);
    }

    @Test
    void testGetStatus_InactiveConnection() {
        // Arrange
        when(peerClient.isRunning()).thenReturn(false);
        when(chainState.getHeaderTip()).thenReturn(null);

        // Act
        HeaderSyncManager.HeaderSyncStatus status = headerSyncManager.getStatus();

        // Assert
        assertFalse(status.active);
        assertEquals(0, status.headersReceived);
        assertNull(status.lastHeaderSlot);
        assertNull(status.lastHeaderBlockNumber);
        assertNull(status.currentHeaderTip);
    }

    @Test
    void testResetMetrics() {
        // Arrange - add some headers first
        byte[] headerBytes = new byte[]{1, 2, 3, 4, 5};
        headerSyncManager.rollforward(new Tip(new Point(1000, "tip"), 500L),
                                     createMockShelleyHeader(1000L, 500L, "block"), headerBytes);
        assertEquals(1, headerSyncManager.getHeadersReceived());

        // Act
        headerSyncManager.resetMetrics();

        // Assert
        assertEquals(0, headerSyncManager.getHeadersReceived());
        HeaderSyncManager.HeaderMetrics metrics = headerSyncManager.getHeaderMetrics();
        assertEquals(0, metrics.totalHeaders);
        assertEquals(0, metrics.shelleyHeaders);
        assertEquals(0, metrics.byronHeaders);
        assertEquals(0, metrics.byronEbHeaders);
    }

    // ================================================================
    // Helper Methods for Creating Mock Objects
    // ================================================================

    private BlockHeader createMockShelleyHeader(long slot, long blockNumber, String hash) {
        HeaderBody headerBody = mock(HeaderBody.class);
        when(headerBody.getSlot()).thenReturn(slot);
        when(headerBody.getBlockNumber()).thenReturn(blockNumber);
        when(headerBody.getBlockHash()).thenReturn(hash);

        BlockHeader blockHeader = mock(BlockHeader.class);
        when(blockHeader.getHeaderBody()).thenReturn(headerBody);

        return blockHeader;
    }

    private ByronBlockHead createMockByronMainBlockHead(long absoluteSlot, long blockNumber, String hash) {
        // Create mock Epoch (contains epoch and slot)
        Epoch slotId = mock(Epoch.class);
        when(slotId.getEpoch()).thenReturn(absoluteSlot / 21600); // Approximate epoch
        when(slotId.getSlot()).thenReturn(absoluteSlot % 21600);  // Approximate slot in epoch

        // Create mock consensus data
        ByronBlockCons consensusData = mock(ByronBlockCons.class);
        when(consensusData.getSlotId()).thenReturn(slotId);
        when(consensusData.getAbsoluteSlot()).thenReturn(absoluteSlot);
        when(consensusData.getDifficulty()).thenReturn(BigInteger.valueOf(blockNumber));

        // Create mock Byron block head
        ByronBlockHead byronHead = mock(ByronBlockHead.class);
        when(byronHead.getConsensusData()).thenReturn(consensusData);
        when(byronHead.getBlockHash()).thenReturn(hash);

        return byronHead;
    }

    private ByronEbHead createMockByronEbBlockHead(long absoluteSlot, long blockNumber, String hash) {
        // Create mock consensus data for EB (Epoch Boundary) block
        ByronEbBlockCons consensusData = mock(ByronEbBlockCons.class);
        when(consensusData.getAbsoluteSlot()).thenReturn(absoluteSlot);
        when(consensusData.getEpoch()).thenReturn(absoluteSlot / 21600); // Approximate epoch
        when(consensusData.getDifficulty()).thenReturn(BigInteger.valueOf(blockNumber));

        // Create mock Byron EB head
        ByronEbHead byronEbHead = mock(ByronEbHead.class);
        when(byronEbHead.getConsensusData()).thenReturn(consensusData);
        when(byronEbHead.getBlockHash()).thenReturn(hash);

        return byronEbHead;
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
            // Mock implementation - no-op for HeaderSyncManager tests
        }

        @Override
        public void startHeaderSync(Point from) {
            // Mock implementation - no-op for HeaderSyncManager tests
        }

        @Override
        public void startHeaderSync(Point from, boolean isPipelined) {
            // Mock implementation - no-op for HeaderSyncManager tests
        }
    }
}
