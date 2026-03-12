package com.bloxbean.cardano.yaci.node.runtime.appmsg;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import com.bloxbean.cardano.yaci.events.api.support.AnnotationListenerRegistrar;
import com.bloxbean.cardano.yaci.events.impl.SimpleEventBus;
import com.bloxbean.cardano.yaci.node.api.consensus.AppConsensus;
import com.bloxbean.cardano.yaci.node.api.consensus.ConsensusMode;
import com.bloxbean.cardano.yaci.node.api.events.AppBlockConsensusEvent;
import com.bloxbean.cardano.yaci.node.api.events.AppBlockProducedEvent;
import com.bloxbean.cardano.yaci.node.api.events.AppDataFinalizedEvent;
import com.bloxbean.cardano.yaci.node.api.events.AppDataValidateEvent;
import com.bloxbean.cardano.yaci.node.api.ledger.AppBlock;
import com.bloxbean.cardano.yaci.node.api.ledger.AppLedger;
import com.bloxbean.cardano.yaci.node.api.validation.app.AppValidationResult;
import com.bloxbean.cardano.yaci.node.runtime.consensus.DefaultAppConsensusListener;
import com.bloxbean.cardano.yaci.node.runtime.consensus.MultiSigConsensus;
import com.bloxbean.cardano.yaci.node.runtime.consensus.SingleSignerConsensus;
import com.bloxbean.cardano.yaci.node.runtime.ledger.InMemoryAppLedger;
import com.bloxbean.cardano.yaci.node.runtime.ledger.RocksDBAppLedger;
import com.bloxbean.cardano.yaci.node.runtime.validation.app.DefaultAppDataValidator;
import com.bloxbean.cardano.yaci.node.runtime.validation.app.DefaultAppValidationListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M2 Integration Test: Full end-to-end flow testing.
 * submit messages → scheduled block production → ledger storage → event notification.
 *
 * Tests multiple topics, consensus modes, validation, and persistence.
 */
class AppBlockProducerIntegrationTest {

    private DefaultAppMessageMemPool memPool;
    private SimpleEventBus eventBus;
    private ScheduledExecutorService scheduler;

    @BeforeEach
    void setup() {
        memPool = new DefaultAppMessageMemPool(1000);
        eventBus = new SimpleEventBus();
        scheduler = Executors.newScheduledThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void fullFlow_singleTopic_singleSigner() throws Exception {
        // Setup
        InMemoryAppLedger ledger = new InMemoryAppLedger();
        SingleSignerConsensus consensus = new SingleSignerConsensus();
        registerDefaultListeners(consensus);

        // Track events
        List<AppBlockProducedEvent> blockEvents = new CopyOnWriteArrayList<>();
        List<AppDataFinalizedEvent> finalizedEvents = new CopyOnWriteArrayList<>();
        CountDownLatch blockLatch = new CountDownLatch(1);

        eventBus.subscribe(AppBlockProducedEvent.class, ctx -> {
            blockEvents.add(ctx.event());
            blockLatch.countDown();
        }, SubscriptionOptions.builder().build());
        eventBus.subscribe(AppDataFinalizedEvent.class, ctx -> finalizedEvents.add(ctx.event()),
                SubscriptionOptions.builder().build());

        // Submit messages
        submitMessages("chat", 5);

        // Create and start producer
        AppBlockProducer producer = new AppBlockProducer(
                memPool, ledger, consensus, eventBus, scheduler, "chat", 200);
        producer.start();

        try {
            // Wait for block production
            assertThat(blockLatch.await(5, TimeUnit.SECONDS)).isTrue();

            // Verify block stored in ledger
            Optional<AppBlock> block = ledger.getBlock("chat", 0);
            assertThat(block).isPresent();
            assertThat(block.get().messageCount()).isEqualTo(5);
            assertThat(block.get().getConsensusProof()).isNotNull();
            assertThat(block.get().getConsensusProof().getMode()).isEqualTo(ConsensusMode.SINGLE_SIGNER);
            assertThat(block.get().getBlockHash()).isNotNull();
            assertThat(block.get().getTopicId()).isEqualTo("chat");

            // Verify events
            assertThat(blockEvents).hasSize(1);
            assertThat(blockEvents.get(0).topicId()).isEqualTo("chat");
            assertThat(blockEvents.get(0).messageCount()).isEqualTo(5);
            assertThat(finalizedEvents).hasSize(5);

            // Verify mempool: messages removed after block inclusion.
            // The block in AppLedger is the durable record; mempool copy no longer needed.
            assertThat(memPool.size()).isEqualTo(0);

            // Verify tip
            assertThat(ledger.getTip("chat")).isPresent();
            assertThat(ledger.getTip("chat").get().getBlockNumber()).isEqualTo(0);
        } finally {
            producer.stop();
        }
    }

    @Test
    void fullFlow_multipleBlocks_chainLinkage() throws Exception {
        InMemoryAppLedger ledger = new InMemoryAppLedger();
        SingleSignerConsensus consensus = new SingleSignerConsensus();
        registerDefaultListeners(consensus);

        CountDownLatch blockLatch = new CountDownLatch(2);
        eventBus.subscribe(AppBlockProducedEvent.class, ctx -> blockLatch.countDown(),
                SubscriptionOptions.builder().build());

        // Submit first batch
        submitMessages("orders", 3);

        AppBlockProducer producer = new AppBlockProducer(
                memPool, ledger, consensus, eventBus, scheduler, "orders", 150);
        producer.start();

        try {
            // Wait a bit for first block, then submit more
            Thread.sleep(500);
            submitMessages("orders", 2, 100);

            assertThat(blockLatch.await(5, TimeUnit.SECONDS)).isTrue();

            // Verify chain linkage
            Optional<AppBlock> block0 = ledger.getBlock("orders", 0);
            Optional<AppBlock> block1 = ledger.getBlock("orders", 1);
            assertThat(block0).isPresent();
            assertThat(block1).isPresent();
            assertThat(block1.get().getPrevBlockHash()).isEqualTo(block0.get().getBlockHash());
            assertThat(block0.get().messageCount()).isEqualTo(3);
            assertThat(block1.get().messageCount()).isEqualTo(2);
        } finally {
            producer.stop();
        }
    }

    @Test
    void fullFlow_multipleTopics_isolatedProducers() throws Exception {
        InMemoryAppLedger ledger = new InMemoryAppLedger();
        SingleSignerConsensus consensus = new SingleSignerConsensus();
        registerDefaultListeners(consensus);

        CountDownLatch blockLatch = new CountDownLatch(2); // one block per topic
        eventBus.subscribe(AppBlockProducedEvent.class, ctx -> blockLatch.countDown(),
                SubscriptionOptions.builder().build());

        // Submit to different topics
        submitMessages("topic-a", 3);
        submitMessages("topic-b", 2);

        AppBlockProducer producerA = new AppBlockProducer(
                memPool, ledger, consensus, eventBus, scheduler, "topic-a", 200);
        AppBlockProducer producerB = new AppBlockProducer(
                memPool, ledger, consensus, eventBus, scheduler, "topic-b", 200);

        producerA.start();
        producerB.start();

        try {
            assertThat(blockLatch.await(5, TimeUnit.SECONDS)).isTrue();

            // Each topic should have its own block chain
            assertThat(ledger.getBlock("topic-a", 0).get().messageCount()).isEqualTo(3);
            assertThat(ledger.getBlock("topic-b", 0).get().messageCount()).isEqualTo(2);

            // Topics should not interfere with each other
            assertThat(ledger.getTip("topic-a").get().getBlockNumber()).isEqualTo(0);
            assertThat(ledger.getTip("topic-b").get().getBlockNumber()).isEqualTo(0);
        } finally {
            producerA.stop();
            producerB.stop();
        }
    }

    @Test
    void fullFlow_withValidation_rejectsInvalidMessages() throws Exception {
        InMemoryAppLedger ledger = new InMemoryAppLedger();
        SingleSignerConsensus consensus = new SingleSignerConsensus();

        // Register consensus listener
        AnnotationListenerRegistrar.register(eventBus,
                new DefaultAppConsensusListener(consensus),
                SubscriptionOptions.builder().build());

        // Register custom validator that rejects messages containing "SPAM"
        var customValidator = new DefaultAppValidationListener(
                (msg, ctx) -> {
                    String body = new String(msg.getMessageBody());
                    if (body.contains("SPAM")) {
                        return AppValidationResult.rejected("spam detected");
                    }
                    return AppValidationResult.ACCEPTED;
                });
        AnnotationListenerRegistrar.register(eventBus, customValidator,
                SubscriptionOptions.builder().build());

        CountDownLatch blockLatch = new CountDownLatch(1);
        eventBus.subscribe(AppBlockProducedEvent.class, ctx -> blockLatch.countDown(),
                SubscriptionOptions.builder().build());

        // Submit mix of valid and invalid messages
        memPool.addMessage(createMessage("filtered", "msg-0", "hello world"));
        memPool.addMessage(createMessage("filtered", "msg-1", "SPAM message"));
        memPool.addMessage(createMessage("filtered", "msg-2", "valid data"));
        memPool.addMessage(createMessage("filtered", "msg-3", "more SPAM here"));

        AppBlockProducer producer = new AppBlockProducer(
                memPool, ledger, consensus, eventBus, scheduler, "filtered", 200);
        producer.start();

        try {
            assertThat(blockLatch.await(5, TimeUnit.SECONDS)).isTrue();

            Optional<AppBlock> block = ledger.getBlock("filtered", 0);
            assertThat(block).isPresent();
            // Only 2 messages should pass validation (msg-0 and msg-2)
            assertThat(block.get().messageCount()).isEqualTo(2);
        } finally {
            producer.stop();
        }
    }

    @Test
    void fullFlow_withRocksDBLedger(@TempDir Path tempDir) throws Exception {
        RocksDBAppLedger ledger = new RocksDBAppLedger(tempDir.resolve("app-ledger").toString());
        SingleSignerConsensus consensus = new SingleSignerConsensus();
        registerDefaultListeners(consensus);

        CountDownLatch blockLatch = new CountDownLatch(1);
        eventBus.subscribe(AppBlockProducedEvent.class, ctx -> blockLatch.countDown(),
                SubscriptionOptions.builder().build());

        submitMessages("persistent", 4);

        AppBlockProducer producer = new AppBlockProducer(
                memPool, ledger, consensus, eventBus, scheduler, "persistent", 200);
        producer.start();

        try {
            assertThat(blockLatch.await(5, TimeUnit.SECONDS)).isTrue();

            // Verify block persisted in RocksDB
            Optional<AppBlock> block = ledger.getBlock("persistent", 0);
            assertThat(block).isPresent();
            assertThat(block.get().messageCount()).isEqualTo(4);
            assertThat(block.get().getConsensusProof()).isNotNull();

            // Verify tip persisted
            assertThat(ledger.getTip("persistent")).isPresent();
        } finally {
            producer.stop();
            ledger.close();
        }
    }

    @Test
    void fullFlow_multiSigConsensus() throws Exception {
        InMemoryAppLedger ledger = new InMemoryAppLedger();

        // Create single-signer but as multisig with threshold=1 (self-signing)
        var params = com.bloxbean.cardano.yaci.node.api.consensus.ConsensusParams.builder()
                .threshold(1).totalSigners(1).build();
        MultiSigConsensus consensus = new MultiSigConsensus(
                List.of(), // empty allowed keys — will not be able to verify
                params);

        // For threshold=1, we need to allow the local key
        MultiSigConsensus verifier = new MultiSigConsensus(
                List.of(consensus.getLocalPublicKey()), params);

        // Register the verifier as the consensus listener
        AnnotationListenerRegistrar.register(eventBus,
                new DefaultAppConsensusListener(verifier),
                SubscriptionOptions.builder().build());
        AnnotationListenerRegistrar.register(eventBus,
                new DefaultAppValidationListener(new DefaultAppDataValidator()),
                SubscriptionOptions.builder().build());

        CountDownLatch blockLatch = new CountDownLatch(1);
        eventBus.subscribe(AppBlockProducedEvent.class, ctx -> blockLatch.countDown(),
                SubscriptionOptions.builder().build());

        submitMessages("multisig-topic", 3);

        // Use consensus (the proposer) for block production, verifier for consensus check
        AppBlockProducer producer = new AppBlockProducer(
                memPool, ledger, consensus, eventBus, scheduler, "multisig-topic", 200);
        producer.start();

        try {
            assertThat(blockLatch.await(5, TimeUnit.SECONDS)).isTrue();

            Optional<AppBlock> block = ledger.getBlock("multisig-topic", 0);
            assertThat(block).isPresent();
            assertThat(block.get().getConsensusProof().getMode()).isEqualTo(ConsensusMode.MULTI_SIG);
            assertThat(block.get().messageCount()).isEqualTo(3);
        } finally {
            producer.stop();
        }
    }

    @Test
    void resumeFromExistingTip() throws Exception {
        InMemoryAppLedger ledger = new InMemoryAppLedger();
        SingleSignerConsensus consensus = new SingleSignerConsensus();
        registerDefaultListeners(consensus);

        // Produce first block manually
        submitMessages("resume-topic", 2);
        AppBlockProducer producer1 = new AppBlockProducer(
                memPool, ledger, consensus, eventBus, scheduler, "resume-topic", 5000);
        producer1.produceBlock();
        producer1.stop();

        assertThat(ledger.getTip("resume-topic")).isPresent();
        assertThat(ledger.getTip("resume-topic").get().getBlockNumber()).isEqualTo(0);

        // Mempool is now empty after block0 commit (messages removed after block inclusion).
        // Verify that before adding new messages.
        assertThat(memPool.size()).isEqualTo(0);

        // Create a NEW producer — it should resume from existing tip
        CountDownLatch blockLatch = new CountDownLatch(1);
        eventBus.subscribe(AppBlockProducedEvent.class, ctx -> blockLatch.countDown(),
                SubscriptionOptions.builder().build());

        submitMessages("resume-topic", 3, 100);
        AppBlockProducer producer2 = new AppBlockProducer(
                memPool, ledger, consensus, eventBus, scheduler, "resume-topic", 200);
        producer2.start();

        try {
            assertThat(blockLatch.await(5, TimeUnit.SECONDS)).isTrue();

            // Should have produced block #1 (resuming from tip #0)
            Optional<AppBlock> block1 = ledger.getBlock("resume-topic", 1);
            assertThat(block1).isPresent();
            assertThat(block1.get().messageCount()).isEqualTo(3);

            // Block #1 should chain to block #0
            Optional<AppBlock> block0 = ledger.getBlock("resume-topic", 0);
            assertThat(block1.get().getPrevBlockHash()).isEqualTo(block0.get().getBlockHash());
        } finally {
            producer2.stop();
        }
    }

    // --- Helpers ---

    private void registerDefaultListeners(AppConsensus consensus) {
        AnnotationListenerRegistrar.register(eventBus,
                new DefaultAppConsensusListener(consensus),
                SubscriptionOptions.builder().build());
        AnnotationListenerRegistrar.register(eventBus,
                new DefaultAppValidationListener(new DefaultAppDataValidator()),
                SubscriptionOptions.builder().build());
    }

    private void submitMessages(String topicId, int count) {
        submitMessages(topicId, count, 0);
    }

    private void submitMessages(String topicId, int count, int offset) {
        for (int i = 0; i < count; i++) {
            memPool.addMessage(AppMessage.builder()
                    .messageId(("msg-" + topicId + "-" + (i + offset)).getBytes())
                    .messageBody(("data-" + (i + offset)).getBytes())
                    .authMethod(0)
                    .authProof(new byte[0])
                    .topicId(topicId)
                    .expiresAt(0)
                    .build());
        }
    }

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
}
