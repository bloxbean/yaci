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
        public void storeBlock(byte[] blockHash, long blockNumber, long slot, byte[] block) {
            // Test implementation - do nothing
        }
        
        @Override
        public byte[] getBlock(byte[] blockHash) {
            return null; // Test implementation
        }
        
        @Override
        public void storeBlockHeader(byte[] blockHash, byte[] blockHeader) {
            // Test implementation - do nothing
        }
        
        @Override
        public byte[] getBlockHeader(byte[] blockHash) {
            return null; // Test implementation
        }
        
        @Override
        public byte[] getBlockByNumber(long blockNumber) {
            return null; // Test implementation
        }
        
        @Override
        public void rollbackTo(long slot) {
            // Test implementation - do nothing
        }
        
        @Override
        public ChainTip getTip() {
            return null; // Test implementation
        }
        
        @Override
        public byte[] getBlockHeaderByNumber(long blockNumber) {
            return null; // Test implementation
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
        public Long getBlockNumberBySlot(long slot) {
            return null; // Test implementation
        }
    }
}
