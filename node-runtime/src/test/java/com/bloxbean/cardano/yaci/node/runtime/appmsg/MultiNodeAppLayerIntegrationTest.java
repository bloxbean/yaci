package com.bloxbean.cardano.yaci.node.runtime.appmsg;

import com.bloxbean.cardano.yaci.core.network.TCPNodeClient;
import com.bloxbean.cardano.yaci.core.network.server.AgentFactory;
import com.bloxbean.cardano.yaci.core.network.server.NodeServer;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgSubmissionAgent;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgSubmissionConfig;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgSubmissionListener;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgSubmissionServerAgent;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.messages.MsgReplyMessageIds;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.messages.MsgReplyMessages;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.messages.MsgRequestMessageIds;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.messages.MsgRequestMessages;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.Reason;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import com.bloxbean.cardano.yaci.events.api.support.AnnotationListenerRegistrar;
import com.bloxbean.cardano.yaci.events.impl.SimpleEventBus;
import com.bloxbean.cardano.yaci.node.api.events.AppBlockProducedEvent;
import com.bloxbean.cardano.yaci.node.api.events.AppDataFinalizedEvent;
import com.bloxbean.cardano.yaci.node.api.ledger.AppBlock;
import com.bloxbean.cardano.yaci.node.runtime.consensus.DefaultAppConsensusListener;
import com.bloxbean.cardano.yaci.node.runtime.consensus.SingleSignerConsensus;
import com.bloxbean.cardano.yaci.node.runtime.ledger.InMemoryAppLedger;
import com.bloxbean.cardano.yaci.node.runtime.validation.app.DefaultAppDataValidator;
import com.bloxbean.cardano.yaci.node.runtime.validation.app.DefaultAppValidationListener;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Multi-node integration test for the full M1 + M2 pipeline:
 *
 * Client Node (sender):
 *   - Enqueues app messages into AppMsgSubmissionAgent
 *   - Connects to server via Protocol 100 (N2N gossip)
 *   - Server pulls messages via the pull-based gossip protocol
 *
 * Server Node (receiver + block producer):
 *   - Receives app messages via Protocol 100 → server mempool
 *   - AppBlockProducer periodically drains mempool
 *   - Validates messages → creates consensus proof → stores block in AppLedger
 *   - Publishes AppBlockProducedEvent and AppDataFinalizedEvent
 *
 * This tests the complete data flow across the network boundary.
 */
@Slf4j
class MultiNodeAppLayerIntegrationTest {

    private static final int SERVER_PORT = 23458;
    private static final long PROTOCOL_MAGIC = 42;
    private static final String TOPIC = "integration-topic";

    @Test
    @Timeout(30)
    void fullPipeline_clientGossipsToServer_serverProducesBlock() throws Exception {
        // === SERVER-SIDE SETUP ===
        // Components: mempool, event bus, consensus, ledger, block producer
        DefaultAppMessageMemPool serverMemPool = new DefaultAppMessageMemPool(1000);
        SimpleEventBus serverEventBus = new SimpleEventBus();
        SingleSignerConsensus consensus = new SingleSignerConsensus();
        InMemoryAppLedger ledger = new InMemoryAppLedger();
        ScheduledExecutorService serverScheduler = Executors.newScheduledThreadPool(2);

        // Register consensus + validation listeners
        AnnotationListenerRegistrar.register(serverEventBus,
                new DefaultAppConsensusListener(consensus),
                SubscriptionOptions.builder().build());
        AnnotationListenerRegistrar.register(serverEventBus,
                new DefaultAppValidationListener(new DefaultAppDataValidator()),
                SubscriptionOptions.builder().build());

        // Track events
        List<AppBlockProducedEvent> blockEvents = new CopyOnWriteArrayList<>();
        List<AppDataFinalizedEvent> finalizedEvents = new CopyOnWriteArrayList<>();
        CountDownLatch blockProducedLatch = new CountDownLatch(1);

        serverEventBus.subscribe(AppBlockProducedEvent.class, ctx -> {
            blockEvents.add(ctx.event());
            blockProducedLatch.countDown();
        }, SubscriptionOptions.builder().build());
        serverEventBus.subscribe(AppDataFinalizedEvent.class, ctx -> finalizedEvents.add(ctx.event()),
                SubscriptionOptions.builder().build());

        // Server agent factory — receives messages from client into mempool
        AppMsgSubmissionConfig serverAgentConfig = AppMsgSubmissionConfig.builder()
                .batchSize(10)
                .useBlockingMode(true)
                .build();

        CountDownLatch messagesInMempool = new CountDownLatch(3);

        AgentFactory appMsgFactory = () -> {
            AppMsgSubmissionServerAgent serverAgent = new AppMsgSubmissionServerAgent(serverAgentConfig);
            serverAgent.addListener(new AppMsgSubmissionListener() {
                @Override
                public void handleReplyMessageIds(MsgReplyMessageIds reply) {
                    log.info("Server: received {} message IDs from client", reply.getMessageIds().size());
                }

                @Override
                public void handleReplyMessages(MsgReplyMessages reply) {
                    log.info("Server: received {} messages from client", reply.getMessages().size());
                    for (AppMessage msg : reply.getMessages()) {
                        boolean added = serverMemPool.addMessage(msg);
                        log.info("Server: message {} added to mempool: {} (pool size: {})",
                                msg.getMessageIdHex(), added, serverMemPool.size());
                        if (added) messagesInMempool.countDown();
                    }
                }
            });
            return serverAgent;
        };

        // Start NodeServer
        NodeServer server = new NodeServer(SERVER_PORT,
                N2NVersionTableConstant.v11AndAbove(PROTOCOL_MAGIC, false, 0, false),
                new MinimalChainState(),
                null, null,
                List.of(appMsgFactory));

        Thread serverThread = new Thread(server::start);
        serverThread.setDaemon(true);
        serverThread.start();
        Thread.sleep(1500);

        try {
            // === CLIENT-SIDE: Send messages via Protocol 100 ===
            AppMsgSubmissionAgent clientAgent = new AppMsgSubmissionAgent();
            clientAgent.addListener(new AppMsgSubmissionListener() {
                @Override
                public void handleRequestMessageIds(MsgRequestMessageIds request) {
                    clientAgent.sendNextMessage();
                }
                @Override
                public void handleRequestMessages(MsgRequestMessages request) {
                    clientAgent.sendNextMessage();
                }
                @Override
                public void handleReplyMessageIds(MsgReplyMessageIds reply) {}
                @Override
                public void handleReplyMessages(MsgReplyMessages reply) {}
            });

            // Create 3 test messages
            AppMessage msg1 = createMessage(TOPIC, "msg-1", "Hello from client");
            AppMessage msg2 = createMessage(TOPIC, "msg-2", "Second message");
            AppMessage msg3 = createMessage(TOPIC, "msg-3", "Third message");

            HandshakeAgent handshakeAgent = new HandshakeAgent(
                    N2NVersionTableConstant.v11AndAbove(PROTOCOL_MAGIC, false, 0, false), true);

            CountDownLatch handshakeLatch = new CountDownLatch(1);
            AtomicBoolean handshakeOk = new AtomicBoolean(false);

            handshakeAgent.addListener(new HandshakeAgentListener() {
                @Override
                public void handshakeOk() {
                    log.info("Client: handshake OK — enqueuing messages");
                    handshakeOk.set(true);
                    clientAgent.enqueueMessage(msg1);
                    clientAgent.enqueueMessage(msg2);
                    clientAgent.enqueueMessage(msg3);
                    handshakeLatch.countDown();
                    clientAgent.sendNextMessage(); // Send MsgInit
                }

                @Override
                public void handshakeError(Reason reason) {
                    log.error("Client: handshake failed: {}", reason);
                    handshakeLatch.countDown();
                }
            });

            TCPNodeClient client = new TCPNodeClient("localhost", SERVER_PORT,
                    handshakeAgent, clientAgent);
            client.start();

            // Wait for handshake
            assertThat(handshakeLatch.await(10, TimeUnit.SECONDS))
                    .as("Handshake should complete").isTrue();
            assertThat(handshakeOk.get()).as("Handshake should succeed").isTrue();

            // Wait for all 3 messages to arrive in server mempool
            assertThat(messagesInMempool.await(10, TimeUnit.SECONDS))
                    .as("All 3 messages should arrive in server mempool").isTrue();

            log.info("=== All messages received in server mempool (size={}) ===", serverMemPool.size());

            // === BLOCK PRODUCTION: Start AppBlockProducer on server ===
            AppBlockProducer producer = new AppBlockProducer(
                    serverMemPool, ledger, consensus, serverEventBus,
                    serverScheduler, TOPIC, 200);
            producer.start();

            try {
                // Wait for block to be produced
                assertThat(blockProducedLatch.await(10, TimeUnit.SECONDS))
                        .as("Block should be produced").isTrue();

                // === VERIFY THE COMPLETE PIPELINE ===

                // 1. Block stored in ledger
                Optional<AppBlock> block = ledger.getBlock(TOPIC, 0);
                assertThat(block).as("Block should be stored in ledger").isPresent();
                assertThat(block.get().getTopicId()).isEqualTo(TOPIC);
                assertThat(block.get().messageCount()).isEqualTo(3);
                assertThat(block.get().getBlockHash()).isNotNull();
                assertThat(block.get().getConsensusProof()).isNotNull();
                assertThat(block.get().getConsensusProof().meetsThreshold()).isTrue();

                // 2. Chain linkage (first block has no prev)
                assertThat(block.get().getPrevBlockHash()).isNull();
                assertThat(block.get().getBlockNumber()).isEqualTo(0);

                // 3. Events published
                assertThat(blockEvents).hasSize(1);
                assertThat(blockEvents.get(0).topicId()).isEqualTo(TOPIC);
                assertThat(blockEvents.get(0).blockNumber()).isEqualTo(0);
                assertThat(blockEvents.get(0).messageCount()).isEqualTo(3);

                assertThat(finalizedEvents).hasSize(3);
                for (AppDataFinalizedEvent fe : finalizedEvents) {
                    assertThat(fe.topicId()).isEqualTo(TOPIC);
                    assertThat(fe.blockNumber()).isEqualTo(0);
                }

                // 4. Mempool: messages removed after block inclusion.
                // The block in AppLedger is the durable record; mempool copy no longer needed.
                assertThat(serverMemPool.size()).isEqualTo(0);

                // 5. Tip updated
                assertThat(ledger.getTip(TOPIC)).isPresent();
                assertThat(ledger.getTip(TOPIC).get().getBlockNumber()).isEqualTo(0);

                // 6. Verify message bodies are preserved through the pipeline
                List<AppMessage> storedMessages = block.get().getMessages();
                assertThat(storedMessages).hasSize(3);
                List<String> bodies = storedMessages.stream()
                        .map(m -> new String(m.getMessageBody()))
                        .toList();
                assertThat(bodies).containsExactlyInAnyOrder(
                        "Hello from client", "Second message", "Third message");

                log.info("=== Multi-node integration test PASSED ===");
                log.info("Pipeline verified: client gossip → server mempool → validation → " +
                        "consensus → block #{} (3 messages) → ledger → events",
                        block.get().getBlockNumber());

            } finally {
                producer.stop();
            }

            client.shutdown();

        } finally {
            server.shutdown();
            serverScheduler.shutdownNow();
        }
    }

    // --- Helpers ---

    private AppMessage createMessage(String topicId, String id, String body) {
        return AppMessage.builder()
                .messageId(id.getBytes())
                .messageBody(body.getBytes())
                .authMethod(0)
                .authProof(new byte[0])
                .topicId(topicId)
                .expiresAt(0)
                .build();
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
