package com.bloxbean.cardano.yaci.node.runtime.consensus;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.node.api.consensus.BlockProposal;
import com.bloxbean.cardano.yaci.node.api.consensus.BlockVote;
import com.bloxbean.cardano.yaci.node.api.consensus.ConsensusMode;
import com.bloxbean.cardano.yaci.node.api.consensus.ConsensusProof;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConsensusRoundTest {

    private BlockProposal createProposal(long blockNumber, byte[] blockHash) {
        return BlockProposal.builder()
                .blockNumber(blockNumber)
                .topicId("test-topic")
                .timestamp(System.currentTimeMillis())
                .prevBlockHash(null)
                .stateHash(new byte[]{1})
                .blockHash(blockHash)
                .proposerKey(new byte[]{10})
                .proposerSignature(new byte[]{20})
                .messages(List.of(
                        AppMessage.builder()
                                .messageId(new byte[]{1})
                                .messageBody(new byte[]{2})
                                .authMethod(0)
                                .authProof(new byte[0])
                                .topicId("test-topic")
                                .expiresAt(0)
                                .build()
                ))
                .build();
    }

    @Test
    void addVote_deduplicatesBySameKey() {
        ConsensusRound round = new ConsensusRound(
                createProposal(0, new byte[]{1, 2, 3}), 2, 30000);

        BlockVote vote1 = BlockVote.create(new byte[]{1, 2, 3}, 0, "test-topic",
                new byte[]{10}, new byte[]{99});
        BlockVote vote1Dup = BlockVote.create(new byte[]{1, 2, 3}, 0, "test-topic",
                new byte[]{10}, new byte[]{88}); // same signer key

        assertThat(round.addVote(vote1)).isTrue();
        assertThat(round.addVote(vote1Dup)).isFalse(); // duplicate
        assertThat(round.getVotes()).hasSize(1);
    }

    @Test
    void addVote_acceptsDifferentKeys() {
        ConsensusRound round = new ConsensusRound(
                createProposal(0, new byte[]{1, 2, 3}), 2, 30000);

        BlockVote vote1 = BlockVote.create(new byte[]{1, 2, 3}, 0, "test-topic",
                new byte[]{10}, new byte[]{99});
        BlockVote vote2 = BlockVote.create(new byte[]{1, 2, 3}, 0, "test-topic",
                new byte[]{20}, new byte[]{77});

        assertThat(round.addVote(vote1)).isTrue();
        assertThat(round.addVote(vote2)).isTrue();
        assertThat(round.getVotes()).hasSize(2);
    }

    @Test
    void isThresholdMet_whenEnoughVotes() {
        ConsensusRound round = new ConsensusRound(
                createProposal(0, new byte[]{1}), 2, 30000);

        round.addVote(BlockVote.create(new byte[]{1}, 0, "t", new byte[]{10}, new byte[]{99}));
        assertThat(round.isThresholdMet()).isFalse();

        round.addVote(BlockVote.create(new byte[]{1}, 0, "t", new byte[]{20}, new byte[]{77}));
        assertThat(round.isThresholdMet()).isTrue();
    }

    @Test
    void isThresholdMet_threshold1() {
        ConsensusRound round = new ConsensusRound(
                createProposal(0, new byte[]{1}), 1, 30000);

        round.addVote(BlockVote.create(new byte[]{1}, 0, "t", new byte[]{10}, new byte[]{99}));
        assertThat(round.isThresholdMet()).isTrue();
    }

    @Test
    void isTimedOut_afterTimeout() throws InterruptedException {
        ConsensusRound round = new ConsensusRound(
                createProposal(0, new byte[]{1}), 2, 50); // 50ms timeout

        assertThat(round.isTimedOut()).isFalse();
        Thread.sleep(100);
        assertThat(round.isTimedOut()).isTrue();
    }

    @Test
    void addVote_rejectedAfterFinalized() {
        ConsensusRound round = new ConsensusRound(
                createProposal(0, new byte[]{1}), 1, 30000);
        round.markFinalized();

        BlockVote vote = BlockVote.create(new byte[]{1}, 0, "t", new byte[]{10}, new byte[]{99});
        assertThat(round.addVote(vote)).isFalse();
    }

    @Test
    void addVote_rejectedAfterTimedOut() {
        ConsensusRound round = new ConsensusRound(
                createProposal(0, new byte[]{1}), 1, 30000);
        round.markTimedOut();

        BlockVote vote = BlockVote.create(new byte[]{1}, 0, "t", new byte[]{10}, new byte[]{99});
        assertThat(round.addVote(vote)).isFalse();
    }

    @Test
    void buildAggregatedProof_collectsAllVotes() {
        ConsensusRound round = new ConsensusRound(
                createProposal(0, new byte[]{1}), 2, 30000);

        round.addVote(BlockVote.create(new byte[]{1}, 0, "t", new byte[]{10}, new byte[]{99}));
        round.addVote(BlockVote.create(new byte[]{1}, 0, "t", new byte[]{20}, new byte[]{77}));

        ConsensusProof proof = round.buildAggregatedProof();
        assertThat(proof.getMode()).isEqualTo(ConsensusMode.MULTI_SIG);
        assertThat(proof.getSignatures()).hasSize(2);
        assertThat(proof.getSignerKeys()).hasSize(2);
        assertThat(proof.getThreshold()).isEqualTo(2);
        assertThat(proof.getProposerKey()).isEqualTo(new byte[]{10}); // from proposal
    }

    @Test
    void buildFinalizedBlock_hasProofAndMessages() {
        BlockProposal proposal = createProposal(5, new byte[]{99});
        ConsensusRound round = new ConsensusRound(proposal, 1, 30000);
        round.addVote(BlockVote.create(new byte[]{99}, 5, "test-topic", new byte[]{10}, new byte[]{99}));

        var block = round.buildFinalizedBlock();
        assertThat(block.getBlockNumber()).isEqualTo(5);
        assertThat(block.getTopicId()).isEqualTo("test-topic");
        assertThat(block.getBlockHash()).isEqualTo(new byte[]{99});
        assertThat(block.getConsensusProof()).isNotNull();
        assertThat(block.getConsensusProof().signatureCount()).isEqualTo(1);
        assertThat(block.getMessages()).hasSize(1);
    }

    @Test
    void stateTransitions() {
        ConsensusRound round = new ConsensusRound(
                createProposal(0, new byte[]{1}), 2, 30000);

        assertThat(round.getState()).isEqualTo(ConsensusRound.State.PROPOSED);

        round.addVote(BlockVote.create(new byte[]{1}, 0, "t", new byte[]{10}, new byte[]{99}));
        assertThat(round.getState()).isEqualTo(ConsensusRound.State.VOTING);

        round.markFinalized();
        assertThat(round.getState()).isEqualTo(ConsensusRound.State.FINALIZED);
    }
}
