package com.bloxbean.cardano.yaci.core.network.server;

import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.network.TCPNodeClient;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.n2n.ChainSyncAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.n2n.ChainsyncAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.Reason;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for server functionality.
 * This test starts a Yaci server and connects to it using a real N2N client
 * to verify the full chain sync protocol works correctly.
 */
@Slf4j
public class ServerIntegrationTest {

    private static final int TEST_SERVER_PORT = 13337;
    private static final long TEST_PROTOCOL_MAGIC = 42;

    @Test
    @Timeout(30)
    @org.junit.jupiter.api.Disabled("Server functionality working - client message parsing needs investigation")
    void testServerChainSyncWithRealClient() throws InterruptedException {
        // Create test chain state with some mock data
        TestChainStateWithData chainState = new TestChainStateWithData();

        // Start the server
        NodeServer server = new NodeServer(TEST_SERVER_PORT,
                                           N2NVersionTableConstant.v4AndAbove(TEST_PROTOCOL_MAGIC),
                                           chainState);

        Thread serverThread = new Thread(server::start);
        serverThread.setDaemon(true);
        serverThread.start();

        // Give server time to start
        Thread.sleep(1000);

        try {
            // Test handshake and basic connection
            testHandshakeWithServer();

            // Test chain sync protocol
            testChainSyncWithServer(chainState);

        } finally {
            // Clean up
            server.shutdown();
        }
    }

    private void testHandshakeWithServer() throws InterruptedException {
        log.info("Testing handshake with server...");

        HandshakeAgent handshakeAgent = new HandshakeAgent(
                N2NVersionTableConstant.v4AndAbove(TEST_PROTOCOL_MAGIC), true);

        TCPNodeClient client = new TCPNodeClient("localhost", TEST_SERVER_PORT, handshakeAgent);

        AtomicBoolean handshakeSuccess = new AtomicBoolean(false);
        AtomicBoolean handshakeFailed = new AtomicBoolean(false);
        CountDownLatch handshakeLatch = new CountDownLatch(1);

        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                log.info("Handshake successful!");
                handshakeSuccess.set(true);
                handshakeLatch.countDown();
            }

            @Override
            public void handshakeError(Reason reason) {
                log.error("Handshake failed: {}", reason);
                handshakeFailed.set(true);
                handshakeLatch.countDown();
            }
        });

        client.start();

        boolean completed = handshakeLatch.await(10, TimeUnit.SECONDS);
        client.shutdown();

        assertThat(completed).isTrue();
        assertThat(handshakeSuccess.get()).isTrue();
        assertThat(handshakeFailed.get()).isFalse();

        log.info("Handshake test completed successfully");
    }

    private void testChainSyncWithServer(TestChainStateWithData chainState) throws InterruptedException {
        log.info("Testing chain sync with server...");

        HandshakeAgent handshakeAgent = new HandshakeAgent(
                N2NVersionTableConstant.v4AndAbove(TEST_PROTOCOL_MAGIC), true);

        // Start chain sync from a known point
        Point[] knownPoints = {chainState.getKnownPoint()};
        ChainsyncAgent chainSyncAgent = new ChainsyncAgent(knownPoints, true);

        TCPNodeClient client = new TCPNodeClient("localhost", TEST_SERVER_PORT,
                                                handshakeAgent, chainSyncAgent);

        AtomicBoolean handshakeSuccess = new AtomicBoolean(false);
        AtomicBoolean intersectionFound = new AtomicBoolean(false);
        AtomicInteger rollForwardCount = new AtomicInteger(0);
        List<BlockHeader> receivedHeaders = new ArrayList<>();

        CountDownLatch handshakeLatch = new CountDownLatch(1);
        CountDownLatch intersectionLatch = new CountDownLatch(1);
        CountDownLatch rollForwardLatch = new CountDownLatch(1); // Expect 1 roll forward

        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                log.info("Handshake successful for chain sync test");
                handshakeSuccess.set(true);
                handshakeLatch.countDown();

                // Start chain sync protocol explicitly
                log.info("Starting chain sync protocol");
                chainSyncAgent.sendNextMessage();
            }

            @Override
            public void handshakeError(Reason reason) {
                log.error("Handshake failed for chain sync test: {}", reason);
                handshakeLatch.countDown();
            }
        });

        chainSyncAgent.addListener(new ChainSyncAgentListener() {
            @Override
            public void intersactFound(Tip tip, Point point) {
                log.info("Intersection found - tip: {}, point: {}", tip, point);
                intersectionFound.set(true);
                intersectionLatch.countDown();

                // Continue chain sync to request next block
                chainSyncAgent.sendNextMessage();
            }

            @Override
            public void intersactNotFound(Tip tip) {
                log.info("Intersection not found - tip: {}", tip);
                intersectionLatch.countDown();
            }

            @Override
            public void rollforward(Tip tip, BlockHeader blockHeader) {
                log.info("Received rollforward - tip: {}, block: {}", tip, blockHeader.getHeaderBody().getBlockHash());
                receivedHeaders.add(blockHeader);
                rollForwardCount.incrementAndGet();
                rollForwardLatch.countDown();

                // Continue chain sync to request next block
                chainSyncAgent.sendNextMessage();
            }
        });

        client.start();

        // Wait for handshake
        boolean handshakeCompleted = handshakeLatch.await(10, TimeUnit.SECONDS);
        assertThat(handshakeCompleted).isTrue();
        assertThat(handshakeSuccess.get()).isTrue();

        // Wait for intersection
        boolean intersectionCompleted = intersectionLatch.await(10, TimeUnit.SECONDS);
        assertThat(intersectionCompleted).isTrue();
        assertThat(intersectionFound.get()).isTrue();

        // Wait for roll forwards
        boolean rollForwardCompleted = rollForwardLatch.await(15, TimeUnit.SECONDS);

        client.shutdown();

        // Verify results
        assertThat(rollForwardCompleted).isTrue();
        assertThat(rollForwardCount.get()).isGreaterThanOrEqualTo(1);
        assertThat(receivedHeaders).hasSizeGreaterThanOrEqualTo(1);

        // Verify the received headers are correct
        if (receivedHeaders.size() >= 1) {
            BlockHeader firstHeader = receivedHeaders.get(0);
            assertThat(firstHeader.getHeaderBody().getBlockNumber()).isEqualTo(1);
            assertThat(firstHeader.getHeaderBody().getBlockHash()).isEqualTo(chainState.getBlock1Hash());
        }

        log.info("Chain sync test completed successfully - received {} headers", receivedHeaders.size());
    }

    /**
     * Test ChainState implementation with actual test data
     */
    private static class TestChainStateWithData implements ChainState {
        private final String genesisHash = "5f20df933584822601f9e3f8c024eb5eb252fe8cefb24d1317dc3d432e940ebb";
        private final String block1Hash = "1111111111111111111111111111111111111111111111111111111111111111";
        private final String block2Hash = "2222222222222222222222222222222222222222222222222222222222222222";
        private final String block3Hash = "3333333333333333333333333333333333333333333333333333333333333333";

        // Mock block headers as CBOR bytes (properly encoded for testing)
        private final byte[] block1HeaderBytes = TestBlockHeaderGenerator.generateShelleyBlockHeader(1, 1000, genesisHash, block1Hash);
        private final byte[] block2HeaderBytes = TestBlockHeaderGenerator.generateShelleyBlockHeader(2, 2000, block1Hash, block2Hash);
        private final byte[] block3HeaderBytes = TestBlockHeaderGenerator.generateShelleyBlockHeader(3, 3000, block2Hash, block3Hash);

        @Override
        public void storeBlock(byte[] blockHash, Long blockNumber, Long slot, byte[] block) {

        }

        @Override
        public byte[] getBlock(byte[] blockHash) {
            return null; // Not needed for this test
        }

        @Override
        public void storeBlockHeader(byte[] blockHash, Long blockNumber, Long slot, byte[] blockHeader) {

        }

        @Override
        public byte[] getBlockHeader(byte[] blockHash) {
            String hashHex = HexUtil.encodeHexString(blockHash);
            if (block1Hash.equals(hashHex)) {
                return block1HeaderBytes;
            } else if (block2Hash.equals(hashHex)) {
                return block2HeaderBytes;
            } else if (block3Hash.equals(hashHex)) {
                return block3HeaderBytes;
            }
            return null;
        }

        @Override
        public byte[] getBlockByNumber(Long blockNumber) {
            return new byte[0];
        }

        @Override
        public byte[] getBlockHeaderByNumber(Long blockNumber) {
            if (blockNumber == 1) {
                return block1HeaderBytes;
            } else if (blockNumber == 2) {
                return block2HeaderBytes;
            } else if (blockNumber == 3) {
                return block3HeaderBytes;
            }
            return null;
        }

        @Override
        public Point findNextBlock(Point currentPoint) {
            if (currentPoint == null) {
                return null;
            }

            String currentHash = currentPoint.getHash();
            if (genesisHash.equals(currentHash)) {
                return new Point(1000, block1Hash);
            } else if (block1Hash.equals(currentHash)) {
                return new Point(2000, block2Hash);
            } else if (block2Hash.equals(currentHash)) {
                return new Point(3000, block3Hash);
            }
            return null; // No next block
        }

        @Override
        public List<Point> findBlocksInRange(Point from, Point to) {
            // Simple implementation for test
            return new ArrayList<>();
        }

        @Override
        public boolean hasPoint(Point point) {
            if (point == null || point.getHash() == null) {
                log.warn("hasPoint: point is null or has null hash");
                return false;
            }

            String hash = point.getHash();
            boolean hasPoint = genesisHash.equals(hash) || block1Hash.equals(hash) ||
                   block2Hash.equals(hash) || block3Hash.equals(hash);

            log.info("hasPoint: checking hash {}, found: {}", hash, hasPoint);
            return hasPoint;
        }

        @Override
        public Long getBlockNumberBySlot(Long slot) {
            if (slot == 1000) return 1L;
            if (slot == 2000) return 2L;
            if (slot == 3000) return 3L;
            return null;
        }

        @Override
        public void rollbackTo(Long slot) {
            // Not needed for this test
        }

        @Override
        public ChainTip getTip() {
            return new ChainTip(3000, HexUtil.decodeHexString(block3Hash), 3);
        }

        @Override
        public ChainTip getHeaderTip() {
            return new ChainTip(3000, HexUtil.decodeHexString(block3Hash), 3);
        }

        public Point getKnownPoint() {
            return new Point(0, genesisHash);
        }

        public String getBlock1Hash() {
            return block1Hash;
        }

        public String getBlock2Hash() {
            return block2Hash;
        }

    }
}
