package com.bloxbean.cardano.yaci.node.runtime.consensus;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import com.bloxbean.cardano.yaci.events.impl.SimpleEventBus;
import com.bloxbean.cardano.yaci.node.api.consensus.*;
import com.bloxbean.cardano.yaci.node.api.events.AppBlockProducedEvent;
import com.bloxbean.cardano.yaci.node.api.events.AppDataFinalizedEvent;
import com.bloxbean.cardano.yaci.node.api.ledger.AppBlock;
import com.bloxbean.cardano.yaci.node.runtime.appmsg.DefaultAppMessageMemPool;
import com.bloxbean.cardano.yaci.node.runtime.ledger.InMemoryAppLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

class AppConsensusCoordinatorTest {

    private InMemoryAppLedger ledger;
    private DefaultAppMessageMemPool memPool;
    private SimpleEventBus eventBus;
    private ScheduledExecutorService scheduler;
    private static final String TOPIC = "test-topic";

    @BeforeEach
    void setup() {
        ledger = new InMemoryAppLedger();
        memPool = new DefaultAppMessageMemPool(1000);
        eventBus = new SimpleEventBus();
        scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    // --- SingleSigner tests ---

    @Test
    void singleSigner_proposeAndFinalize() {
        SingleSignerConsensus consensus = new SingleSignerConsensus();
        AppConsensusCoordinator coordinator = new AppConsensusCoordinator(
                consensus, ledger, memPool, eventBus, TOPIC, scheduler);
        coordinator.start();

        AppBlock candidate = buildCandidateBlock(0, TOPIC, null);
        coordinator.proposeBlock(candidate);

        // Block should be stored in ledger
        assertThat(ledger.getBlock(TOPIC, 0)).isPresent();
        assertThat(ledger.getBlock(TOPIC, 0).get().getConsensusProof()).isNotNull();
        assertThat(coordinator.getNextBlockNumber()).isEqualTo(1);

        // Finalized block should be gossipped to mempool
        assertThat(memPool.size()).isGreaterThan(0);
        boolean hasFinalizedMsg = memPool.getMessages(100).stream()
                .anyMatch(m -> m.getTopicId().equals(TOPIC + "::finalized"));
        assertThat(hasFinalizedMsg).isTrue();

        coordinator.stop();
    }

    @Test
    void singleSigner_publishesEvents() {
        SingleSignerConsensus consensus = new SingleSignerConsensus();
        AppConsensusCoordinator coordinator = new AppConsensusCoordinator(
                consensus, ledger, memPool, eventBus, TOPIC, scheduler);
        coordinator.start();

        List<AppBlockProducedEvent> blockEvents = new CopyOnWriteArrayList<>();
        List<AppDataFinalizedEvent> finalizedEvents = new CopyOnWriteArrayList<>();
        eventBus.subscribe(AppBlockProducedEvent.class, ctx -> blockEvents.add(ctx.event()),
                SubscriptionOptions.builder().build());
        eventBus.subscribe(AppDataFinalizedEvent.class, ctx -> finalizedEvents.add(ctx.event()),
                SubscriptionOptions.builder().build());

        AppBlock candidate = buildCandidateBlock(0, TOPIC, null);
        coordinator.proposeBlock(candidate);

        assertThat(blockEvents).hasSize(1);
        assertThat(blockEvents.get(0).topicId()).isEqualTo(TOPIC);
        assertThat(finalizedEvents).hasSize(2); // 2 messages in candidate

        coordinator.stop();
    }

    @Test
    void singleSigner_chainsBlocks() {
        SingleSignerConsensus consensus = new SingleSignerConsensus();
        AppConsensusCoordinator coordinator = new AppConsensusCoordinator(
                consensus, ledger, memPool, eventBus, TOPIC, scheduler);
        coordinator.start();

        AppBlock block0 = buildCandidateBlock(0, TOPIC, null);
        coordinator.proposeBlock(block0);

        byte[] block0Hash = ledger.getBlock(TOPIC, 0).get().getBlockHash();
        AppBlock block1 = buildCandidateBlock(1, TOPIC, block0Hash);
        coordinator.proposeBlock(block1);

        assertThat(coordinator.getNextBlockNumber()).isEqualTo(2);
        assertThat(ledger.getBlock(TOPIC, 1)).isPresent();
        assertThat(ledger.getBlock(TOPIC, 1).get().getPrevBlockHash()).isEqualTo(block0Hash);

        coordinator.stop();
    }

    // --- MultiSig tests ---

    @Test
    void multiSig_proposeAndVoteAndFinalize() {
        // Setup 2 validators with threshold=2
        SingleSignerConsensus node1Consensus = new SingleSignerConsensus();
        SingleSignerConsensus node2Consensus = new SingleSignerConsensus();

        // Create a MultiSig consensus that accepts any key (empty allowedKeys)
        ConsensusParams params = ConsensusParams.builder()
                .threshold(2).totalSigners(2).timeoutMs(30000).build();
        MultiSigConsensus multiSig = new MultiSigConsensus(List.of(), params);

        AppConsensusCoordinator coordinator = new AppConsensusCoordinator(
                multiSig, ledger, memPool, eventBus, TOPIC, scheduler);
        coordinator.start();

        // Propose
        AppBlock candidate = buildCandidateBlock(0, TOPIC, null);
        coordinator.proposeBlock(candidate);

        // Should have proposal in mempool, block NOT yet finalized (need 2 votes, only have 1)
        assertThat(ledger.getBlock(TOPIC, 0)).isEmpty();
        assertThat(coordinator.activeRoundCount()).isEqualTo(1);

        // Simulate peer vote
        BlockVote peerVote = BlockVote.create(
                candidate.getBlockHash(), 0, TOPIC,
                node2Consensus.getLocalPublicKey(),
                node2Consensus.sign(candidate.getBlockHash()));
        coordinator.handleVote(peerVote);

        // Now threshold is met, block should be finalized
        assertThat(ledger.getBlock(TOPIC, 0)).isPresent();
        assertThat(ledger.getBlock(TOPIC, 0).get().getConsensusProof()).isNotNull();
        assertThat(ledger.getBlock(TOPIC, 0).get().getConsensusProof().signatureCount()).isEqualTo(2);
        assertThat(coordinator.activeRoundCount()).isEqualTo(0);

        coordinator.stop();
    }

    @Test
    void multiSig_followerReceivesProposal() {
        // Follower node (not the proposer)
        SingleSignerConsensus followerConsensus = new SingleSignerConsensus();
        ConsensusParams params = ConsensusParams.builder()
                .threshold(1).totalSigners(2).timeoutMs(30000).build();
        // Use follower's consensus for the coordinator
        AppConsensusCoordinator followerCoordinator = new AppConsensusCoordinator(
                followerConsensus, ledger, memPool, eventBus, TOPIC, scheduler);
        followerCoordinator.start();

        // Simulate receiving a proposal from a remote proposer
        AppBlock candidate = buildCandidateBlock(0, TOPIC, null);
        SingleSignerConsensus proposerConsensus = new SingleSignerConsensus();
        byte[] proposerSig = proposerConsensus.sign(candidate.getBlockHash());
        BlockProposal proposal = BlockProposal.fromAppBlock(
                candidate, proposerConsensus.getLocalPublicKey(), proposerSig);

        followerCoordinator.handleProposal(proposal);

        // Follower should have voted (vote in mempool) and since threshold=1, finalized
        assertThat(ledger.getBlock(TOPIC, 0)).isPresent();
        assertThat(followerCoordinator.getNextBlockNumber()).isEqualTo(1);

        // Vote should be in mempool
        boolean hasVoteMsg = memPool.getMessages(100).stream()
                .anyMatch(m -> m.getTopicId().equals(TOPIC + "::vote"));
        assertThat(hasVoteMsg).isTrue();

        followerCoordinator.stop();
    }

    // --- Finalized block reception tests ---

    @Test
    void followerReceivesFinalizedBlock() {
        SingleSignerConsensus consensus = new SingleSignerConsensus();
        AppConsensusCoordinator coordinator = new AppConsensusCoordinator(
                consensus, ledger, memPool, eventBus, TOPIC, scheduler);
        coordinator.start();

        // Build a finalized block externally
        AppBlock candidate = buildCandidateBlock(0, TOPIC, null);
        ConsensusProof proof = consensus.createProof(candidate);
        AppBlock finalBlock = AppBlock.builder()
                .blockNumber(candidate.getBlockNumber())
                .topicId(candidate.getTopicId())
                .messages(candidate.getMessages())
                .stateHash(candidate.getStateHash())
                .timestamp(candidate.getTimestamp())
                .prevBlockHash(candidate.getPrevBlockHash())
                .blockHash(candidate.getBlockHash())
                .consensusProof(proof)
                .build();

        FinalizedAppBlock finalized = FinalizedAppBlock.builder()
                .block(finalBlock).proof(proof).build();

        coordinator.handleFinalizedBlock(finalized);

        assertThat(ledger.getBlock(TOPIC, 0)).isPresent();
        assertThat(coordinator.getNextBlockNumber()).isEqualTo(1);

        coordinator.stop();
    }

    @Test
    void followerRejectsFinalizedBlock_badProof() {
        SingleSignerConsensus consensus = new SingleSignerConsensus();
        // Use a MultiSig consensus that requires proper verification
        ConsensusParams params = ConsensusParams.builder()
                .threshold(1).totalSigners(1).timeoutMs(30000).build();
        MultiSigConsensus verifyingConsensus = new MultiSigConsensus(
                List.of(consensus.getLocalPublicKey()), params);

        AppConsensusCoordinator coordinator = new AppConsensusCoordinator(
                verifyingConsensus, ledger, memPool, eventBus, TOPIC, scheduler);
        coordinator.start();

        AppBlock candidate = buildCandidateBlock(0, TOPIC, null);
        // Create a bad proof (wrong signature)
        ConsensusProof badProof = ConsensusProof.builder()
                .mode(ConsensusMode.MULTI_SIG)
                .proposerKey(new byte[]{1, 2, 3})
                .signatures(List.of(new byte[]{99, 99, 99}))
                .signerKeys(List.of(new byte[]{1, 2, 3}))
                .threshold(1)
                .build();

        AppBlock badBlock = AppBlock.builder()
                .blockNumber(0).topicId(TOPIC)
                .messages(candidate.getMessages())
                .stateHash(candidate.getStateHash())
                .timestamp(candidate.getTimestamp())
                .blockHash(candidate.getBlockHash())
                .consensusProof(badProof)
                .build();

        FinalizedAppBlock finalized = FinalizedAppBlock.builder()
                .block(badBlock).proof(badProof).build();

        coordinator.handleFinalizedBlock(finalized);

        // Block should NOT be stored
        assertThat(ledger.getBlock(TOPIC, 0)).isEmpty();
        assertThat(coordinator.getNextBlockNumber()).isEqualTo(0);

        coordinator.stop();
    }

    @Test
    void followerRejectsFinalizedBlock_wrongBlockNumber() {
        SingleSignerConsensus consensus = new SingleSignerConsensus();
        AppConsensusCoordinator coordinator = new AppConsensusCoordinator(
                consensus, ledger, memPool, eventBus, TOPIC, scheduler);
        coordinator.start();

        // Try to store block #5 when tip is at 0
        AppBlock candidate = buildCandidateBlock(5, TOPIC, null);
        ConsensusProof proof = consensus.createProof(candidate);
        AppBlock block = AppBlock.builder()
                .blockNumber(5).topicId(TOPIC)
                .messages(candidate.getMessages())
                .stateHash(candidate.getStateHash())
                .timestamp(candidate.getTimestamp())
                .blockHash(candidate.getBlockHash())
                .consensusProof(proof)
                .build();

        coordinator.handleFinalizedBlock(FinalizedAppBlock.builder()
                .block(block).proof(proof).build());

        assertThat(ledger.getBlock(TOPIC, 5)).isEmpty();

        coordinator.stop();
    }

    @Test
    void duplicateFinalizedBlock_isIgnored() {
        SingleSignerConsensus consensus = new SingleSignerConsensus();
        AppConsensusCoordinator coordinator = new AppConsensusCoordinator(
                consensus, ledger, memPool, eventBus, TOPIC, scheduler);
        coordinator.start();

        AppBlock candidate = buildCandidateBlock(0, TOPIC, null);
        coordinator.proposeBlock(candidate); // stores block 0

        // Try to receive finalized block 0 again
        var stored = ledger.getBlock(TOPIC, 0).get();
        FinalizedAppBlock finalized = FinalizedAppBlock.builder()
                .block(stored).proof(stored.getConsensusProof()).build();
        coordinator.handleFinalizedBlock(finalized);

        // Should still be at block 1, not advanced further
        assertThat(coordinator.getNextBlockNumber()).isEqualTo(1);

        coordinator.stop();
    }

    @Test
    void multiSig_rejectsProposalFromNonDesignatedProposer() {
        // Setup MultiSig with 2 known keys, threshold=1
        SingleSignerConsensus designatedProposer = new SingleSignerConsensus();
        SingleSignerConsensus wrongProposer = new SingleSignerConsensus();

        List<byte[]> allowedKeys = List.of(
                designatedProposer.getLocalPublicKey(),
                wrongProposer.getLocalPublicKey()
        );
        ConsensusParams params = ConsensusParams.builder()
                .threshold(1).totalSigners(2).timeoutMs(30000).build();
        MultiSigConsensus multiSig = new MultiSigConsensus(allowedKeys, params);

        AppConsensusCoordinator coordinator = new AppConsensusCoordinator(
                multiSig, ledger, memPool, eventBus, TOPIC, scheduler);
        coordinator.start();

        // Determine who is the designated proposer for block #0
        boolean designatedIsFirst = multiSig.isExpectedProposer(0, designatedProposer.getLocalPublicKey());
        SingleSignerConsensus actualDesignated = designatedIsFirst ? designatedProposer : wrongProposer;
        SingleSignerConsensus actualWrong = designatedIsFirst ? wrongProposer : designatedProposer;

        // Build a candidate block and create a proposal from the WRONG proposer
        AppBlock candidate = buildCandidateBlock(0, TOPIC, null);
        byte[] wrongSig = actualWrong.sign(candidate.getBlockHash());
        BlockProposal wrongProposal = BlockProposal.fromAppBlock(
                candidate, actualWrong.getLocalPublicKey(), wrongSig);

        coordinator.handleProposal(wrongProposal);

        // Block should NOT be finalized — proposal was rejected
        assertThat(ledger.getBlock(TOPIC, 0)).isEmpty();

        // Now send from the correct proposer — should work
        byte[] correctSig = actualDesignated.sign(candidate.getBlockHash());
        BlockProposal correctProposal = BlockProposal.fromAppBlock(
                candidate, actualDesignated.getLocalPublicKey(), correctSig);

        coordinator.handleProposal(correctProposal);

        // With threshold=1, should be finalized
        assertThat(ledger.getBlock(TOPIC, 0)).isPresent();

        coordinator.stop();
    }

    // --- Helpers ---

    private AppBlock buildCandidateBlock(long blockNumber, String topicId, byte[] prevHash) {
        List<AppMessage> messages = List.of(
                AppMessage.builder()
                        .messageId(("msg-a-" + blockNumber).getBytes())
                        .messageBody(("data-a-" + blockNumber).getBytes())
                        .authMethod(0).authProof(new byte[0])
                        .topicId(topicId).expiresAt(0).build(),
                AppMessage.builder()
                        .messageId(("msg-b-" + blockNumber).getBytes())
                        .messageBody(("data-b-" + blockNumber).getBytes())
                        .authMethod(0).authProof(new byte[0])
                        .topicId(topicId).expiresAt(0).build()
        );

        byte[] stateHash = AppBlock.computeStateHash(messages);
        long timestamp = System.currentTimeMillis();
        byte[] blockHash = AppBlock.computeBlockHash(blockNumber, topicId, stateHash, prevHash, timestamp);

        return AppBlock.builder()
                .blockNumber(blockNumber)
                .topicId(topicId)
                .messages(messages)
                .stateHash(stateHash)
                .timestamp(timestamp)
                .prevBlockHash(prevHash)
                .blockHash(blockHash)
                .build();
    }
}
