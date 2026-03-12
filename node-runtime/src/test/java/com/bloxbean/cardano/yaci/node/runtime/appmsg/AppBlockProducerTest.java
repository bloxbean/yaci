package com.bloxbean.cardano.yaci.node.runtime.appmsg;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.impl.SimpleEventBus;
import com.bloxbean.cardano.yaci.node.api.consensus.AppConsensus;
import com.bloxbean.cardano.yaci.node.api.events.AppBlockConsensusEvent;
import com.bloxbean.cardano.yaci.node.api.events.AppBlockProducedEvent;
import com.bloxbean.cardano.yaci.node.api.events.AppDataFinalizedEvent;
import com.bloxbean.cardano.yaci.node.api.events.AppDataValidateEvent;
import com.bloxbean.cardano.yaci.node.api.ledger.AppBlock;
import com.bloxbean.cardano.yaci.node.api.ledger.AppLedger;
import com.bloxbean.cardano.yaci.node.api.validation.app.AppValidationResult;
import com.bloxbean.cardano.yaci.node.runtime.consensus.DefaultAppConsensusListener;
import com.bloxbean.cardano.yaci.node.runtime.consensus.SingleSignerConsensus;
import com.bloxbean.cardano.yaci.node.runtime.ledger.InMemoryAppLedger;
import com.bloxbean.cardano.yaci.node.runtime.validation.app.DefaultAppDataValidator;
import com.bloxbean.cardano.yaci.node.runtime.validation.app.DefaultAppValidationListener;
import com.bloxbean.cardano.yaci.events.api.DomainEventListener;
import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import com.bloxbean.cardano.yaci.events.api.support.AnnotationListenerRegistrar;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AppBlockProducerTest {

    private DefaultAppMessageMemPool memPool;
    private InMemoryAppLedger ledger;
    private SingleSignerConsensus consensus;
    private SimpleEventBus eventBus;
    private ScheduledExecutorService scheduler;

    @BeforeEach
    void setup() {
        memPool = new DefaultAppMessageMemPool(1000);
        ledger = new InMemoryAppLedger();
        consensus = new SingleSignerConsensus();
        eventBus = new SimpleEventBus();
        scheduler = Executors.newSingleThreadScheduledExecutor();

        // Register default listeners
        AnnotationListenerRegistrar.register(eventBus, new DefaultAppConsensusListener(consensus), SubscriptionOptions.builder().build());
        AnnotationListenerRegistrar.register(eventBus, new DefaultAppValidationListener(new DefaultAppDataValidator()), SubscriptionOptions.builder().build());
    }

    @Test
    void produceBlock_includesMessages() {
        addTestMessages("test-topic", 3);

        AppBlockProducer producer = new AppBlockProducer(
                memPool, ledger, consensus, eventBus, scheduler, "test-topic", 5000);
        producer.produceBlock();

        // Messages removed from mempool after block inclusion
        assertThat(memPool.size()).isEqualTo(0);
        assertThat(producer.getNextBlockNumber()).isEqualTo(1);

        Optional<AppBlock> block = ledger.getBlock("test-topic", 0);
        assertThat(block).isPresent();
        assertThat(block.get().messageCount()).isEqualTo(3);
        assertThat(block.get().getConsensusProof()).isNotNull();
        assertThat(block.get().getBlockHash()).isNotNull();

        // Second call should produce nothing (all messages already produced)
        producer.produceBlock();
        assertThat(producer.getNextBlockNumber()).isEqualTo(1); // No new block
    }

    @Test
    void produceBlock_skipsEmptyMempool() {
        AppBlockProducer producer = new AppBlockProducer(
                memPool, ledger, consensus, eventBus, scheduler, "test-topic", 5000);
        producer.produceBlock();

        assertThat(producer.getNextBlockNumber()).isEqualTo(0); // No block produced
        assertThat(ledger.getTip("test-topic")).isEmpty();
    }

    @Test
    void produceBlock_chainsBlocks() {
        addTestMessages("chain-topic", 2);
        AppBlockProducer producer = new AppBlockProducer(
                memPool, ledger, consensus, eventBus, scheduler, "chain-topic", 5000);
        producer.produceBlock();

        // Add more messages and produce second block
        addTestMessages("chain-topic", 2, 10); // offset to avoid duplicate IDs
        producer.produceBlock();

        assertThat(producer.getNextBlockNumber()).isEqualTo(2);

        Optional<AppBlock> block0 = ledger.getBlock("chain-topic", 0);
        Optional<AppBlock> block1 = ledger.getBlock("chain-topic", 1);
        assertThat(block0).isPresent();
        assertThat(block1).isPresent();

        // Block 1 should reference block 0
        assertThat(block1.get().getPrevBlockHash()).isEqualTo(block0.get().getBlockHash());
    }

    @Test
    void produceBlock_topicIsolation() {
        addTestMessages("topic-a", 2);
        addTestMessages("topic-b", 3);

        AppBlockProducer producerA = new AppBlockProducer(
                memPool, ledger, consensus, eventBus, scheduler, "topic-a", 5000);
        producerA.produceBlock();

        // Only topic-b messages remain (topic-a's 2 messages removed after block)
        assertThat(memPool.size()).isEqualTo(3);
        assertThat(ledger.getBlock("topic-a", 0).get().messageCount()).isEqualTo(2);
        assertThat(ledger.getTip("topic-b")).isEmpty();
    }

    @Test
    void produceBlock_validationRejectsMessages() {
        addTestMessages("reject-topic", 3);

        // Register a validator that rejects messages with "reject" in body
        var rejectingValidator = new DefaultAppValidationListener(
                (msg, ctx) -> {
                    String body = new String(msg.getMessageBody());
                    if (body.contains("1")) {
                        return AppValidationResult.rejected("contains '1'");
                    }
                    return AppValidationResult.ACCEPTED;
                });
        // Replace default validator by re-creating event bus
        eventBus = new SimpleEventBus();
        AnnotationListenerRegistrar.register(eventBus, new DefaultAppConsensusListener(consensus), SubscriptionOptions.builder().build());
        AnnotationListenerRegistrar.register(eventBus, rejectingValidator, SubscriptionOptions.builder().build());

        AppBlockProducer producer = new AppBlockProducer(
                memPool, ledger, consensus, eventBus, scheduler, "reject-topic", 5000);
        producer.produceBlock();

        Optional<AppBlock> block = ledger.getBlock("reject-topic", 0);
        assertThat(block).isPresent();
        // Message with "1" in body should be rejected (message-1)
        assertThat(block.get().messageCount()).isEqualTo(2); // 0 and 2 pass, 1 rejected
    }

    @Test
    void produceBlock_publishesEvents() {
        List<AppBlockProducedEvent> blockEvents = new CopyOnWriteArrayList<>();
        List<AppDataFinalizedEvent> finalizedEvents = new CopyOnWriteArrayList<>();

        eventBus.subscribe(AppBlockProducedEvent.class, (ctx) -> blockEvents.add(ctx.event()), SubscriptionOptions.builder().build());
        eventBus.subscribe(AppDataFinalizedEvent.class, (ctx) -> finalizedEvents.add(ctx.event()), SubscriptionOptions.builder().build());

        addTestMessages("event-topic", 2);
        AppBlockProducer producer = new AppBlockProducer(
                memPool, ledger, consensus, eventBus, scheduler, "event-topic", 5000);
        producer.produceBlock();

        assertThat(blockEvents).hasSize(1);
        assertThat(blockEvents.get(0).topicId()).isEqualTo("event-topic");
        assertThat(blockEvents.get(0).messageCount()).isEqualTo(2);

        assertThat(finalizedEvents).hasSize(2);
    }

    @Test
    void scheduledProduction() throws Exception {
        addTestMessages("sched-topic", 2);

        CountDownLatch blockLatch = new CountDownLatch(1);
        eventBus.subscribe(AppBlockProducedEvent.class, (ctx) -> blockLatch.countDown(), SubscriptionOptions.builder().build());

        AppBlockProducer producer = new AppBlockProducer(
                memPool, ledger, consensus, eventBus, scheduler, "sched-topic", 200);
        producer.start();

        try {
            assertThat(blockLatch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(ledger.getTip("sched-topic")).isPresent();
        } finally {
            producer.stop();
        }
    }

    // --- Helpers ---

    private void addTestMessages(String topicId, int count) {
        addTestMessages(topicId, count, 0);
    }

    private void addTestMessages(String topicId, int count, int offset) {
        for (int i = 0; i < count; i++) {
            memPool.addMessage(AppMessage.builder()
                    .messageId(("msg-" + topicId + "-" + (i + offset)).getBytes())
                    .messageBody(("message-" + (i + offset)).getBytes())
                    .authMethod(0)
                    .authProof(new byte[0])
                    .topicId(topicId)
                    .expiresAt(0)
                    .build());
        }
    }
}
