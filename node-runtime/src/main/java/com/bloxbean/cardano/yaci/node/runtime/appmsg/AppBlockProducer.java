package com.bloxbean.cardano.yaci.node.runtime.appmsg;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.api.EventMetadata;
import com.bloxbean.cardano.yaci.events.api.PublishOptions;
import com.bloxbean.cardano.yaci.node.api.consensus.AppConsensus;
import com.bloxbean.cardano.yaci.node.api.consensus.ConsensusProof;
import com.bloxbean.cardano.yaci.node.api.events.AppBlockConsensusEvent;
import com.bloxbean.cardano.yaci.node.api.events.AppBlockProducedEvent;
import com.bloxbean.cardano.yaci.node.api.events.AppDataFinalizedEvent;
import com.bloxbean.cardano.yaci.node.api.events.AppDataValidateEvent;
import com.bloxbean.cardano.yaci.node.api.ledger.AppBlock;
import com.bloxbean.cardano.yaci.node.api.ledger.AppLedger;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Produces app blocks by periodically draining the app message mempool.
 * Flow: drain mempool → validate each message → build block → consensus → store → publish events.
 */
@Slf4j
public class AppBlockProducer {

    private final AppMessageMemPool memPool;
    private final AppLedger ledger;
    private final AppConsensus consensus;
    private final EventBus eventBus;
    private final ScheduledExecutorService scheduler;
    private final String topicId;
    private final int blockIntervalMs;

    private final Set<String> producedMessageIds = new HashSet<>();

    private ScheduledFuture<?> scheduledTask;
    private long nextBlockNumber = 0;
    private byte[] prevBlockHash = null;
    private volatile boolean running;

    public AppBlockProducer(AppMessageMemPool memPool, AppLedger ledger, AppConsensus consensus,
                            EventBus eventBus, ScheduledExecutorService scheduler,
                            String topicId, int blockIntervalMs) {
        this.memPool = memPool;
        this.ledger = ledger;
        this.consensus = consensus;
        this.eventBus = eventBus;
        this.scheduler = scheduler;
        this.topicId = topicId;
        this.blockIntervalMs = blockIntervalMs;
    }

    /**
     * Start app block production. Resumes from existing tip if present.
     */
    public void start() {
        if (running) {
            log.warn("AppBlockProducer already running for topic '{}'", topicId);
            return;
        }

        // Resume from existing tip
        ledger.getTip(topicId).ifPresent(tip -> {
            nextBlockNumber = tip.getBlockNumber() + 1;
            prevBlockHash = tip.getBlockHash();
            log.info("AppBlockProducer resuming for topic '{}': block={}", topicId, nextBlockNumber);
        });

        running = true;
        scheduledTask = scheduler.scheduleWithFixedDelay(() -> {
            try {
                produceBlock();
            } catch (Exception e) {
                log.error("Error producing app block for topic '{}'", topicId, e);
            }
        }, blockIntervalMs, blockIntervalMs, TimeUnit.MILLISECONDS);

        log.info("AppBlockProducer started for topic '{}': interval={}ms", topicId, blockIntervalMs);
    }

    /**
     * Stop app block production.
     */
    public void stop() {
        running = false;
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
            scheduledTask = null;
        }
        log.info("AppBlockProducer stopped for topic '{}'", topicId);
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * Produce a single app block from pending messages.
     * Exposed as package-private for testing.
     */
    void produceBlock() {
        if (!consensus.canPropose()) {
            log.debug("Cannot propose block for topic '{}' at this time", topicId);
            return;
        }

        // Get messages for this topic, excluding already-produced ones
        List<AppMessage> allMessages = memPool.getMessagesForTopic(topicId);
        List<AppMessage> candidates = new ArrayList<>();
        for (AppMessage msg : allMessages) {
            if (!producedMessageIds.contains(msg.getMessageIdHex())) {
                candidates.add(msg);
            }
        }
        if (candidates.isEmpty()) {
            return; // Nothing to produce
        }

        // Validate each message
        List<AppMessage> validMessages = validateMessages(candidates);
        if (validMessages.isEmpty()) {
            log.debug("All messages rejected during validation for topic '{}'", topicId);
            return;
        }

        // Build block
        byte[] stateHash = AppBlock.computeStateHash(validMessages);
        long timestamp = System.currentTimeMillis();
        byte[] blockHash = AppBlock.computeBlockHash(nextBlockNumber, topicId, stateHash, prevBlockHash, timestamp);

        AppBlock block = AppBlock.builder()
                .blockNumber(nextBlockNumber)
                .topicId(topicId)
                .messages(validMessages)
                .stateHash(stateHash)
                .timestamp(timestamp)
                .prevBlockHash(prevBlockHash)
                .blockHash(blockHash)
                .build();

        // Create consensus proof
        ConsensusProof proof = consensus.createProof(block);

        // Rebuild block with proof
        block = AppBlock.builder()
                .blockNumber(nextBlockNumber)
                .topicId(topicId)
                .messages(validMessages)
                .stateHash(stateHash)
                .timestamp(timestamp)
                .prevBlockHash(prevBlockHash)
                .blockHash(blockHash)
                .consensusProof(proof)
                .build();

        // Consensus check via event
        if (eventBus != null) {
            AppBlockConsensusEvent consensusEvent = new AppBlockConsensusEvent(block);
            eventBus.publish(consensusEvent,
                    EventMetadata.builder().origin("app-block-producer").build(),
                    PublishOptions.builder().build());

            if (consensusEvent.isRejected()) {
                log.warn("App block #{} rejected by consensus for topic '{}': {}",
                        nextBlockNumber, topicId, consensusEvent.rejections());
                return;
            }
        }

        // Store in ledger
        ledger.storeBlock(block);

        // Track produced messages and remove from mempool.
        // By this point messages have been gossiped (sync runs every 1s, block interval >= 3s).
        // The block in AppLedger is now the durable record.
        for (AppMessage msg : validMessages) {
            producedMessageIds.add(msg.getMessageIdHex());
            memPool.removeMessage(msg.getMessageId());
        }

        long producedBlock = nextBlockNumber;
        nextBlockNumber++;
        prevBlockHash = blockHash;

        log.info("App block #{} produced for topic '{}': messages={}, hash={}",
                producedBlock, topicId, validMessages.size(),
                com.bloxbean.cardano.yaci.core.util.HexUtil.encodeHexString(blockHash));

        // Publish events
        publishEvents(block);
    }

    private List<AppMessage> validateMessages(List<AppMessage> candidates) {
        List<AppMessage> valid = new ArrayList<>();
        for (AppMessage msg : candidates) {
            if (eventBus != null) {
                AppDataValidateEvent validateEvent = new AppDataValidateEvent(msg, topicId, nextBlockNumber);
                eventBus.publish(validateEvent,
                        EventMetadata.builder().origin("app-block-producer").build(),
                        PublishOptions.builder().build());

                if (validateEvent.isRejected()) {
                    log.debug("Message {} rejected during validation: {}",
                            msg.getMessageIdHex(), validateEvent.rejections());
                    continue;
                }
            }
            valid.add(msg);
        }
        return valid;
    }

    private void publishEvents(AppBlock block) {
        if (eventBus == null) return;

        EventMetadata meta = EventMetadata.builder().origin("app-block-producer").build();
        PublishOptions opts = PublishOptions.builder().build();

        // Block produced event
        eventBus.publish(new AppBlockProducedEvent(
                block.getTopicId(), block.getBlockNumber(), block.getBlockHash(),
                block.messageCount(), block.getTimestamp()), meta, opts);

        // Per-message finalized events
        for (AppMessage msg : block.getMessages()) {
            eventBus.publish(new AppDataFinalizedEvent(
                    msg, block.getTopicId(), block.getBlockNumber(), block.getBlockHash()), meta, opts);
        }
    }

    // --- Getters for testing ---

    public long getNextBlockNumber() {
        return nextBlockNumber;
    }

    public String getTopicId() {
        return topicId;
    }
}
