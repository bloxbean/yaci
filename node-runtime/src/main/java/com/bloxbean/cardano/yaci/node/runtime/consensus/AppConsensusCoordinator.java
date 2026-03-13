package com.bloxbean.cardano.yaci.node.runtime.consensus;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.api.EventMetadata;
import com.bloxbean.cardano.yaci.events.api.PublishOptions;
import com.bloxbean.cardano.yaci.node.api.consensus.*;
import com.bloxbean.cardano.yaci.node.api.events.AppBlockProducedEvent;
import com.bloxbean.cardano.yaci.node.api.events.AppDataFinalizedEvent;
import com.bloxbean.cardano.yaci.node.api.ledger.AppBlock;
import com.bloxbean.cardano.yaci.node.api.ledger.AppLedger;
import com.bloxbean.cardano.yaci.node.runtime.appmsg.AppMessageMemPool;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates the consensus lifecycle for a single topic:
 * <ul>
 *   <li>SingleSigner: propose → sign → store → gossip finalized</li>
 *   <li>MultiSig: propose → gossip proposal → collect votes → finalize → gossip finalized</li>
 * </ul>
 * Consensus messages are routed here by YaciAppMessageHandler based on topicId suffixes.
 */
@Slf4j
public class AppConsensusCoordinator {

    private static final int CONSENSUS_MSG_TTL_SECONDS = 60;
    private static final long TIMEOUT_CHECK_INTERVAL_MS = 1000;

    private final AppConsensus consensus;
    private final AppLedger ledger;
    private final AppMessageMemPool memPool;
    private final EventBus eventBus;
    @Getter
    private final String topicId;
    private final ScheduledExecutorService scheduler;

    /** Active consensus rounds keyed by block hash hex */
    private final Map<String, ConsensusRound> activeRounds = new ConcurrentHashMap<>();

    @Getter
    private long nextBlockNumber = 0;
    @Getter
    private byte[] prevBlockHash = null;

    private ScheduledFuture<?> timeoutTask;
    private volatile boolean running;

    public AppConsensusCoordinator(AppConsensus consensus, AppLedger ledger,
                                   AppMessageMemPool memPool, EventBus eventBus,
                                   String topicId, ScheduledExecutorService scheduler) {
        this.consensus = consensus;
        this.ledger = ledger;
        this.memPool = memPool;
        this.eventBus = eventBus;
        this.topicId = topicId;
        this.scheduler = scheduler;
    }

    /**
     * Start the coordinator. Resumes from ledger tip and schedules timeout checker.
     */
    public void start() {
        if (running) return;

        // Resume from existing tip
        ledger.getTip(topicId).ifPresent(tip -> {
            nextBlockNumber = tip.getBlockNumber() + 1;
            prevBlockHash = tip.getBlockHash();
            log.info("AppConsensusCoordinator resuming for topic '{}': block={}", topicId, nextBlockNumber);
        });

        running = true;

        // Schedule periodic timeout check for active rounds
        timeoutTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                checkTimeouts();
            } catch (Exception e) {
                log.warn("Timeout check error for topic '{}': {}", topicId, e.getMessage());
            }
        }, TIMEOUT_CHECK_INTERVAL_MS, TIMEOUT_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);

        log.info("AppConsensusCoordinator started for topic '{}'", topicId);
    }

    /**
     * Stop the coordinator.
     */
    public void stop() {
        running = false;
        if (timeoutTask != null) {
            timeoutTask.cancel(false);
            timeoutTask = null;
        }
        activeRounds.clear();
        log.info("AppConsensusCoordinator stopped for topic '{}'", topicId);
    }

    /**
     * Called by AppBlockProducer when a candidate block is built.
     * For SingleSigner: sign, store, gossip finalized.
     * For MultiSig: sign, gossip proposal, create local round with own vote.
     */
    public synchronized void proposeBlock(AppBlock candidateBlock) {
        if (!running) return;

        byte[] blockHash = candidateBlock.getBlockHash();
        byte[] localKey = consensus.getLocalPublicKey();
        byte[] signature = consensus.sign(blockHash);

        if (consensus.consensusMode() == ConsensusMode.SINGLE_SIGNER) {
            // SingleSigner: immediate finalization
            ConsensusProof proof = ConsensusProof.singleSigner(localKey, signature);
            AppBlock finalBlock = AppBlock.builder()
                    .blockNumber(candidateBlock.getBlockNumber())
                    .topicId(candidateBlock.getTopicId())
                    .messages(candidateBlock.getMessages())
                    .stateHash(candidateBlock.getStateHash())
                    .timestamp(candidateBlock.getTimestamp())
                    .prevBlockHash(candidateBlock.getPrevBlockHash())
                    .blockHash(blockHash)
                    .consensusProof(proof)
                    .build();

            ledger.storeBlock(finalBlock);
            advanceTip(blockHash);

            // Gossip finalized block to peers
            FinalizedAppBlock finalized = FinalizedAppBlock.builder()
                    .block(finalBlock)
                    .proof(proof)
                    .build();
            gossipMessage(finalized.toAppMessage(CONSENSUS_MSG_TTL_SECONDS));

            log.info("SingleSigner block #{} finalized for topic '{}': hash={}",
                    finalBlock.getBlockNumber(), topicId, HexUtil.encodeHexString(blockHash));
            publishEvents(finalBlock);
        } else {
            // MultiSig: gossip proposal, start consensus round
            BlockProposal proposal = BlockProposal.fromAppBlock(candidateBlock, localKey, signature);
            gossipMessage(proposal.toAppMessage(CONSENSUS_MSG_TTL_SECONDS));

            // Create round with own vote
            ConsensusRound round = new ConsensusRound(proposal,
                    consensus.params().getThreshold(), consensus.params().getTimeoutMs());
            BlockVote ownVote = BlockVote.create(blockHash, candidateBlock.getBlockNumber(),
                    topicId, localKey, signature);
            round.addVote(ownVote);
            activeRounds.put(HexUtil.encodeHexString(blockHash), round);

            log.info("MultiSig proposal gossiped for block #{} topic '{}': hash={}",
                    candidateBlock.getBlockNumber(), topicId, HexUtil.encodeHexString(blockHash));

            // Check if threshold already met (e.g., threshold=1)
            if (round.isThresholdMet()) {
                finalizeRound(round);
            }
        }
    }

    /**
     * Handle an incoming block proposal from a peer (MultiSig only).
     * Validate, sign a vote, gossip it.
     */
    public synchronized void handleProposal(BlockProposal proposal) {
        if (!running) return;
        if (!topicId.equals(proposal.getTopicId())) return;

        String blockHashHex = HexUtil.encodeHexString(proposal.getBlockHash());

        // Check if already finalized
        if (isAlreadyFinalized(proposal.getBlockNumber())) {
            log.debug("Ignoring proposal for already-finalized block #{}", proposal.getBlockNumber());
            return;
        }

        // Verify proposal block hash
        byte[] expectedHash = AppBlock.computeBlockHash(proposal.getBlockNumber(), proposal.getTopicId(),
                proposal.getStateHash(), proposal.getPrevBlockHash(), proposal.getTimestamp());
        if (!Arrays.equals(expectedHash, proposal.getBlockHash())) {
            log.warn("Proposal block hash mismatch for block #{}", proposal.getBlockNumber());
            return;
        }

        // Verify proposer is the designated proposer for this block number
        if (!consensus.isExpectedProposer(proposal.getBlockNumber(), proposal.getProposerKey())) {
            log.warn("Rejecting proposal from non-designated proposer for block #{}", proposal.getBlockNumber());
            return;
        }

        // Sign vote
        byte[] localKey = consensus.getLocalPublicKey();
        byte[] voteSig = consensus.sign(proposal.getBlockHash());
        BlockVote vote = BlockVote.create(proposal.getBlockHash(), proposal.getBlockNumber(),
                topicId, localKey, voteSig);

        // Gossip vote
        gossipMessage(vote.toAppMessage(CONSENSUS_MSG_TTL_SECONDS));

        // Track round locally
        ConsensusRound round = activeRounds.computeIfAbsent(blockHashHex,
                k -> new ConsensusRound(proposal, consensus.params().getThreshold(),
                        consensus.params().getTimeoutMs()));
        round.addVote(vote);

        log.debug("Vote sent for block #{} topic '{}': hash={}", proposal.getBlockNumber(), topicId, blockHashHex);

        if (round.isThresholdMet()) {
            finalizeRound(round);
        }
    }

    /**
     * Handle an incoming vote from a peer (MultiSig only).
     */
    public synchronized void handleVote(BlockVote vote) {
        if (!running) return;
        if (!topicId.equals(vote.getTopicId())) return;

        String blockHashHex = vote.blockHashHex();
        ConsensusRound round = activeRounds.get(blockHashHex);
        if (round == null) {
            // No active round yet — buffer the vote by creating a pending round
            // This can happen if votes arrive before the proposal
            log.debug("Vote received for unknown round {}, buffering", blockHashHex);
            return;
        }

        if (round.addVote(vote) && round.isThresholdMet()) {
            finalizeRound(round);
        }
    }

    /**
     * Handle an incoming finalized block from a peer.
     * Verify proof, check it chains to our tip, and store.
     */
    public synchronized void handleFinalizedBlock(FinalizedAppBlock finalized) {
        AppBlock block = finalized.getBlock();
        log.info("handleFinalizedBlock called: block #{}, topic='{}', running={}",
                block.getBlockNumber(), block.getTopicId(), running);
        if (!running) {
            log.info("Coordinator not running for topic '{}', ignoring finalized block #{}",
                    topicId, block.getBlockNumber());
            return;
        }
        ConsensusProof proof = finalized.getProof();

        if (!topicId.equals(block.getTopicId())) return;

        // Skip if we already have this block
        if (isAlreadyFinalized(block.getBlockNumber())) {
            log.debug("Ignoring finalized block #{} — already stored", block.getBlockNumber());
            return;
        }

        // Verify proof
        if (!consensus.verifyProof(block, proof)) {
            log.warn("Rejecting finalized block #{} — proof verification failed", block.getBlockNumber());
            return;
        }

        // Check block chains to current tip
        if (block.getBlockNumber() != nextBlockNumber) {
            log.warn("Finalized block #{} does not match expected #{} for topic '{}'",
                    block.getBlockNumber(), nextBlockNumber, topicId);
            return;
        }
        if (prevBlockHash != null && !Arrays.equals(block.getPrevBlockHash(), prevBlockHash)) {
            log.warn("Finalized block #{} prevBlockHash mismatch for topic '{}'",
                    block.getBlockNumber(), topicId);
            return;
        }

        // Store in ledger
        ledger.storeBlock(block);
        advanceTip(block.getBlockHash());

        // Clean up active round if exists
        String hashHex = HexUtil.encodeHexString(block.getBlockHash());
        ConsensusRound round = activeRounds.remove(hashHex);
        if (round != null) {
            round.markFinalized();
        }

        log.info("Finalized block #{} stored for topic '{}': hash={}, messages={}",
                block.getBlockNumber(), topicId, hashHex, block.messageCount());
        publishEvents(block);
    }

    /**
     * Inject a consensus message into the mempool for Protocol 100 distribution.
     */
    void gossipMessage(AppMessage msg) {
        memPool.addMessage(msg);
    }

    // --- Internal ---

    private void finalizeRound(ConsensusRound round) {
        if (round.getState() == ConsensusRound.State.FINALIZED) return;

        AppBlock finalBlock = round.buildFinalizedBlock();
        round.markFinalized();

        // Store in ledger
        ledger.storeBlock(finalBlock);
        advanceTip(finalBlock.getBlockHash());

        // Gossip finalized block
        FinalizedAppBlock finalized = FinalizedAppBlock.builder()
                .block(finalBlock)
                .proof(finalBlock.getConsensusProof())
                .build();
        gossipMessage(finalized.toAppMessage(CONSENSUS_MSG_TTL_SECONDS));

        // Clean up
        activeRounds.remove(round.blockHashHex());

        log.info("MultiSig block #{} finalized for topic '{}': hash={}, votes={}",
                finalBlock.getBlockNumber(), topicId, round.blockHashHex(),
                round.getVotes().size());
        publishEvents(finalBlock);
    }

    private void advanceTip(byte[] blockHash) {
        nextBlockNumber++;
        prevBlockHash = blockHash;
    }

    private boolean isAlreadyFinalized(long blockNumber) {
        return ledger.getBlock(topicId, blockNumber).isPresent();
    }

    private void checkTimeouts() {
        Iterator<Map.Entry<String, ConsensusRound>> it = activeRounds.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, ConsensusRound> entry = it.next();
            ConsensusRound round = entry.getValue();
            if (round.isTimedOut() && round.getState() != ConsensusRound.State.FINALIZED) {
                round.markTimedOut();
                it.remove();
                log.warn("Consensus round timed out for block #{} topic '{}': hash={}",
                        round.getBlockNumber(), topicId, entry.getKey());
            }
        }
    }

    private void publishEvents(AppBlock block) {
        if (eventBus == null) return;

        EventMetadata meta = EventMetadata.builder().origin("consensus-coordinator").build();
        PublishOptions opts = PublishOptions.builder().build();

        eventBus.publish(new AppBlockProducedEvent(
                block.getTopicId(), block.getBlockNumber(), block.getBlockHash(),
                block.messageCount(), block.getTimestamp()), meta, opts);

        if (block.getMessages() != null) {
            for (AppMessage msg : block.getMessages()) {
                eventBus.publish(new AppDataFinalizedEvent(
                        msg, block.getTopicId(), block.getBlockNumber(), block.getBlockHash()), meta, opts);
            }
        }
    }

    public boolean isRunning() {
        return running;
    }

    public int activeRoundCount() {
        return activeRounds.size();
    }
}
