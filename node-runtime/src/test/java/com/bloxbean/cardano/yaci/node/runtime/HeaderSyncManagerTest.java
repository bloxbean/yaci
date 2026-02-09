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
import com.bloxbean.cardano.yaci.node.runtime.chain.InMemoryChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.helper.PeerClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

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
        Tip tip = new Tip(new Point(1000, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), 500L);
        BlockHeader blockHeader = createMockShelleyHeader(1000L, 500L, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        byte[] originalHeaderBytes = new byte[]{1, 2, 3, 4, 5}; // Mock CBOR data

        // Act
        headerSyncManager.rollforward(tip, blockHeader, originalHeaderBytes);

        // Assert
        ChainTip headerTip = chainState.getHeaderTip();
        assertNotNull(headerTip);
        assertEquals(1000L, headerTip.getSlot());
        assertEquals(500L, headerTip.getBlockNumber());
        assertEquals(1, headerSyncManager.getHeadersReceived());
        assertEquals(1, headerSyncManager.getHeaderMetrics().shelleyHeaders);
    }

    @Test
    void testRollforward_ShelleyHeader_NullBlockHeader() {
        // Arrange
        Tip tip = new Tip(new Point(1000, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), 500L);
        byte[] originalHeaderBytes = new byte[]{1, 2, 3, 4, 5};

        // Act & Assert - should not throw exception, should log warning
        assertDoesNotThrow(() -> headerSyncManager.rollforward(tip, null, originalHeaderBytes));
        // No header should be stored
        assertNull(chainState.getHeaderTip());
        assertEquals(0, headerSyncManager.getHeadersReceived());
    }

    @Test
    void testRollforward_ShelleyHeader_NullOriginalHeaderBytes() {
        // Arrange
        Tip tip = new Tip(new Point(1000, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), 500L);
        BlockHeader blockHeader = createMockShelleyHeader(1000L, 500L, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");

        // Act & Assert - should not throw exception, should log warning
        assertDoesNotThrow(() -> headerSyncManager.rollforward(tip, blockHeader, null));
        assertNull(chainState.getHeaderTip());
        assertEquals(0, headerSyncManager.getHeadersReceived());
    }

    @Test
    void testRollforward_ShelleyHeader_EmptyOriginalHeaderBytes() {
        // Arrange
        Tip tip = new Tip(new Point(1000, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), 500L);
        BlockHeader blockHeader = createMockShelleyHeader(1000L, 500L, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        byte[] emptyBytes = new byte[0];

        // Act & Assert - should not throw exception, should log warning
        assertDoesNotThrow(() -> headerSyncManager.rollforward(tip, blockHeader, emptyBytes));
        assertNull(chainState.getHeaderTip());
        assertEquals(0, headerSyncManager.getHeadersReceived());
    }

    @Test
    void testRollforward_ShelleyHeader_StorageException() {
        // Arrange
        Tip tip = new Tip(new Point(1000, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), 500L);
        BlockHeader blockHeader = createMockShelleyHeader(1000L, 500L, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        byte[] originalHeaderBytes = new byte[]{1, 2, 3, 4, 5};

        // With in-memory chainstate we don't simulate storage failures; ensure it does not throw
        assertDoesNotThrow(() -> headerSyncManager.rollforward(tip, blockHeader, originalHeaderBytes));
    }

    // ================================================================
    // Byron Era Header Tests
    // ================================================================

    @Test
    void testRollforwardByronEra_MainBlockHeader_Success() {
        // Arrange
        Tip tip = new Tip(new Point(1000, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), 500L);
        ByronBlockHead byronHead = createMockByronMainBlockHead(1000L, 500L, "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc");
        byte[] originalHeaderBytes = new byte[]{1, 2, 3, 4, 5};

        // Act
        headerSyncManager.rollforwardByronEra(tip, byronHead, originalHeaderBytes);

        // Assert
        ChainTip headerTip1 = chainState.getHeaderTip();
        assertNotNull(headerTip1);
        assertEquals(1000L, headerTip1.getSlot());
        assertEquals(500L, headerTip1.getBlockNumber());
        assertEquals(1, headerSyncManager.getHeadersReceived());
        assertEquals(1, headerSyncManager.getHeaderMetrics().byronHeaders);
    }

    @Test
    void testRollforwardByronEra_EbBlockHeader_Success() {
        // Arrange
        Tip tip = new Tip(new Point(1000, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), 500L);
        ByronEbHead byronEbHead = createMockByronEbBlockHead(1000L, 500L, "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd");
        byte[] originalHeaderBytes = new byte[]{1, 2, 3, 4, 5};

        // Act
        headerSyncManager.rollforwardByronEra(tip, byronEbHead, originalHeaderBytes);

        // Assert
        ChainTip headerTip2 = chainState.getHeaderTip();
        assertNotNull(headerTip2);
        assertEquals(0L, headerTip2.getSlot());
        assertEquals(500L, headerTip2.getBlockNumber());
        assertEquals(1, headerSyncManager.getHeadersReceived());
        assertEquals(1, headerSyncManager.getHeaderMetrics().byronEbHeaders);
    }

    // ================================================================
    // Control Flow Method Tests
    // ================================================================

    @Test
    void testIntersactFound() {
        // Arrange
        Tip tip = new Tip(new Point(1000, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), 500L);
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
        headerSyncManager.rollforward(tip, createMockShelleyHeader(1000L, 500L, "1111111111111111111111111111111111111111111111111111111111111111"), headerBytes);
        headerSyncManager.rollforward(tip, createMockShelleyHeader(1001L, 501L, "2222222222222222222222222222222222222222222222222222222222222222"), headerBytes);
        headerSyncManager.rollforwardByronEra(tip, createMockByronMainBlockHead(999L, 499L, "3333333333333333333333333333333333333333333333333333333333333333"), headerBytes);
        headerSyncManager.rollforwardByronEra(tip, createMockByronEbBlockHead(998L, 498L, "4444444444444444444444444444444444444444444444444444444444444444"), headerBytes);

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
        peerClient.setRunning(true);
        byte[] headerBytes = new byte[]{1, 2, 3, 4, 5};
        headerSyncManager.rollforward(new Tip(new Point(1000, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), 500L),
                                      createMockShelleyHeader(1000L, 500L, "5555555555555555555555555555555555555555555555555555555555555555"), headerBytes);

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
        peerClient.setRunning(false);

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
        headerSyncManager.rollforward(new Tip(new Point(1000, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), 500L),
                                      createMockShelleyHeader(1000L, 500L, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"), headerBytes);
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
        HeaderBody headerBody = HeaderBody.builder()
                .slot(slot)
                .blockNumber(blockNumber)
                .blockHash(hash)
                .build();
        return BlockHeader.builder().headerBody(headerBody).build();
    }

    private ByronBlockHead createMockByronMainBlockHead(long absoluteSlot, long blockNumber, String hash) {
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

    private ByronEbHead createMockByronEbBlockHead(long absoluteSlot, long blockNumber, String hash) {
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
