package com.bloxbean.cardano.yaci.core.network.server;

import com.bloxbean.cardano.yaci.core.network.TCPNodeClient;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.Reason;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
public class NodeServerTest {

    @Test
    @Disabled
    void startNodeServer_successfulHandshake() throws InterruptedException {
        // Create test chain state
        ChainState chainState = new TestChainState();
        NodeServer server = new NodeServer(3333, N2NVersionTableConstant.v11AndAbove(1), chainState);
        Thread t = new Thread() {
            @Override
            public void run() {
                server.start();
            }
        };

        t.start();

        HandshakeAgent handshakeAgent = new HandshakeAgent(N2NVersionTableConstant.v11AndAbove(1), true);
        TCPNodeClient tcpNodeClient = new TCPNodeClient("localhost", 3333, handshakeAgent);

        AtomicBoolean success = new AtomicBoolean();
        CountDownLatch countDownLatch = new CountDownLatch(1);
        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                log.info("HANDSHAKE Successful");
                countDownLatch.countDown();
                success.set(true);
            }

            @Override
            public void handshakeError(Reason reason) {
                log.info("ERROR {}", reason);
            }
        });

        tcpNodeClient.start();

        countDownLatch.await(10, TimeUnit.SECONDS);
        t.stop();
        assertThat(success).isTrue();
    }

    // Simple test implementation of ChainState
    private static class TestChainState implements ChainState {

        @Override
        public void storeBlock(byte[] blockHash, Long blockNumber, Long slot, byte[] block) {

        }

        @Override
        public byte[] getBlock(byte[] blockHash) {
            return null; // Test implementation
        }

        @Override
        public void storeBlockHeader(byte[] blockHash, Long blockNumber, Long slot, byte[] blockHeader) {

        }

        @Override
        public byte[] getBlockHeader(byte[] blockHash) {
            return null; // Test implementation
        }

        @Override
        public byte[] getBlockByNumber(Long blockNumber) {
            return new byte[0];
        }

        @Override
        public byte[] getBlockHeaderByNumber(Long blockNumber) {
            return new byte[0];
        }

        @Override
        public ChainTip getTip() {
            return null; // Test implementation
        }

        @Override
        public ChainTip getHeaderTip() {
            return null;
        }

        @Override
        public com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point findNextBlock(com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point currentPoint) {
            return null; // Test implementation
        }

        @Override
        public java.util.List<com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point> findBlocksInRange(com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point from, com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point to) {
            return new java.util.ArrayList<>(); // Test implementation
        }

        @Override
        public boolean hasPoint(com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point point) {
            return false; // Test implementation
        }

        @Override
        public Long getBlockNumberBySlot(Long slot) {
            return 0L;
        }

        @Override
        public void rollbackTo(Long slot) {

        }

    }
}
