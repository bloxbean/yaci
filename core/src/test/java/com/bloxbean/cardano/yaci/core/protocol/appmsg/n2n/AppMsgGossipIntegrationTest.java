package com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n;

import com.bloxbean.cardano.yaci.core.network.TCPNodeClient;
import com.bloxbean.cardano.yaci.core.network.server.AgentFactory;
import com.bloxbean.cardano.yaci.core.network.server.NodeServer;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.messages.MsgReplyMessageIds;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.messages.MsgReplyMessages;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.Reason;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: starts a real server with AppMsgSubmissionServerAgent,
 * connects a client with AppMsgSubmissionAgent carrying messages,
 * and verifies the pull-based gossip protocol transfers messages end-to-end.
 */
@Slf4j
class AppMsgGossipIntegrationTest {

    private static final int TEST_PORT = 23457;
    private static final long TEST_MAGIC = 42;

    @Test
    @Timeout(30)
    void testClientToServerMessageGossip() throws Exception {
        // --- Server-side: NodeServer with app-layer agent factory ---
        List<AppMessage> serverReceivedMessages = new CopyOnWriteArrayList<>();
        CountDownLatch messagesReceivedLatch = new CountDownLatch(2); // expect 2 messages

        AppMsgSubmissionConfig serverConfig = AppMsgSubmissionConfig.builder()
                .batchSize(10)
                .useBlockingMode(true)
                .build();

        AgentFactory appMsgFactory = () -> {
            AppMsgSubmissionServerAgent serverAgent = new AppMsgSubmissionServerAgent(serverConfig);
            serverAgent.addListener(new AppMsgSubmissionListener() {
                @Override
                public void handleReplyMessageIds(MsgReplyMessageIds reply) {
                    log.info("Server received {} message IDs", reply.getMessageIds().size());
                }

                @Override
                public void handleReplyMessages(MsgReplyMessages reply) {
                    log.info("Server received {} messages", reply.getMessages().size());
                    for (AppMessage msg : reply.getMessages()) {
                        serverReceivedMessages.add(msg);
                        messagesReceivedLatch.countDown();
                        log.info("Server received message: id={}, topic={}, body-size={}",
                                msg.getMessageIdHex(), msg.getTopicId(), msg.getMessageBody().length);
                    }
                }
            });
            return serverAgent;
        };

        NodeServer server = new NodeServer(TEST_PORT,
                N2NVersionTableConstant.v11AndAbove(TEST_MAGIC, false, 0, false),
                new MinimalChainState(),
                null, null,
                List.of(appMsgFactory));

        Thread serverThread = new Thread(server::start);
        serverThread.setDaemon(true);
        serverThread.start();
        Thread.sleep(1500); // Wait for server to start

        try {
            // --- Client-side: connect with AppMsgSubmissionAgent containing messages ---
            AppMsgSubmissionAgent clientAgent = new AppMsgSubmissionAgent();

            // Wire up the client listener to drive the protocol forward
            // (same pattern as N2NPeerFetcher does for TxSubmissionAgent)
            clientAgent.addListener(new AppMsgSubmissionListener() {
                @Override
                public void handleRequestMessageIds(com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.messages.MsgRequestMessageIds request) {
                    clientAgent.sendNextMessage();
                }

                @Override
                public void handleRequestMessages(com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.messages.MsgRequestMessages request) {
                    clientAgent.sendNextMessage();
                }

                @Override
                public void handleReplyMessageIds(MsgReplyMessageIds reply) {}

                @Override
                public void handleReplyMessages(MsgReplyMessages reply) {}
            });

            // Enqueue messages before connecting
            AppMessage msg1 = AppMessage.builder()
                    .messageId(new byte[]{0x01, 0x02, 0x03, 0x04})
                    .messageBody("Hello from client - message 1".getBytes())
                    .authMethod(0)
                    .authProof(new byte[0])
                    .topicId("test-topic")
                    .expiresAt(0)
                    .build();
            AppMessage msg2 = AppMessage.builder()
                    .messageId(new byte[]{0x05, 0x06, 0x07, 0x08})
                    .messageBody("Hello from client - message 2".getBytes())
                    .authMethod(0)
                    .authProof(new byte[0])
                    .topicId("test-topic")
                    .expiresAt(0)
                    .build();
            HandshakeAgent handshakeAgent = new HandshakeAgent(
                    N2NVersionTableConstant.v11AndAbove(TEST_MAGIC, false, 0, false), true);

            AtomicBoolean handshakeOk = new AtomicBoolean(false);
            CountDownLatch handshakeLatch = new CountDownLatch(1);

            handshakeAgent.addListener(new HandshakeAgentListener() {
                @Override
                public void handshakeOk() {
                    log.info("Client handshake OK - enqueuing messages and sending MsgInit");
                    handshakeOk.set(true);
                    // Enqueue messages AFTER handshake (reset() clears pending queue during connect)
                    clientAgent.enqueueMessage(msg1);
                    clientAgent.enqueueMessage(msg2);
                    handshakeLatch.countDown();
                    // Start app-message protocol after handshake
                    clientAgent.sendNextMessage();
                }

                @Override
                public void handshakeError(Reason reason) {
                    log.error("Client handshake failed: {}", reason);
                    handshakeLatch.countDown();
                }
            });

            TCPNodeClient client = new TCPNodeClient("localhost", TEST_PORT,
                    handshakeAgent, clientAgent);
            client.start();

            // Wait for handshake
            assertThat(handshakeLatch.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(handshakeOk.get()).isTrue();

            // Wait for messages to be pulled by server
            boolean received = messagesReceivedLatch.await(15, TimeUnit.SECONDS);

            client.shutdown();

            // Verify
            assertThat(received).as("Server should have received 2 messages").isTrue();
            assertThat(serverReceivedMessages).hasSize(2);
            assertThat(serverReceivedMessages.get(0).getTopicId()).isEqualTo("test-topic");
            assertThat(new String(serverReceivedMessages.get(0).getMessageBody()))
                    .isEqualTo("Hello from client - message 1");
            assertThat(new String(serverReceivedMessages.get(1).getMessageBody()))
                    .isEqualTo("Hello from client - message 2");

            log.info("App message gossip integration test passed!");

        } finally {
            server.shutdown();
        }
    }

    /**
     * Minimal ChainState for a server that only handles app-layer messaging.
     */
    private static class MinimalChainState implements ChainState {
        @Override public void storeBlock(byte[] blockHash, Long blockNumber, Long slot, byte[] block) {}
        @Override public byte[] getBlock(byte[] blockHash) { return null; }
        @Override public boolean hasBlock(byte[] blockHash) { return false; }
        @Override public void storeBlockHeader(byte[] blockHash, Long blockNumber, Long slot, byte[] blockHeader) {}
        @Override public byte[] getBlockHeader(byte[] blockHash) { return null; }
        @Override public byte[] getBlockByNumber(Long blockNumber) { return null; }
        @Override public byte[] getBlockHeaderByNumber(Long blockNumber) { return null; }
        @Override public Point findNextBlock(Point currentPoint) { return null; }
        @Override public Point findNextBlockHeader(Point currentPoint) { return null; }
        @Override public List<Point> findBlocksInRange(Point from, Point to) { return Collections.emptyList(); }
        @Override public Point findLastPointAfterNBlocks(Point from, long batchSize) { return null; }
        @Override public boolean hasPoint(Point point) { return false; }
        @Override public Point getFirstBlock() { return null; }
        @Override public Long getBlockNumberBySlot(Long slot) { return null; }
        @Override public Long getSlotByBlockNumber(Long blockNumber) { return null; }
        @Override public void rollbackTo(Long slot) {}
        @Override public ChainTip getTip() { return null; }
        @Override public ChainTip getHeaderTip() { return null; }
    }
}
