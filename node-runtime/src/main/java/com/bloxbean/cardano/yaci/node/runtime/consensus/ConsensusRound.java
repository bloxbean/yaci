package com.bloxbean.cardano.yaci.node.runtime.consensus;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.node.api.consensus.*;
import com.bloxbean.cardano.yaci.node.api.ledger.AppBlock;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks one consensus round: a proposal, collected votes, and state.
 * Used by AppConsensusCoordinator to manage MultiSig consensus rounds.
 */
@Slf4j
@Getter
public class ConsensusRound {

    public enum State {
        PROPOSED, VOTING, FINALIZED, TIMED_OUT
    }

    private final byte[] blockHash;
    private final long blockNumber;
    private final String topicId;
    private final BlockProposal proposal;
    private final int threshold;
    private final long createdAtMs;
    private final long timeoutMs;

    /** Votes keyed by signer key hex (for dedup). Preserves insertion order. */
    private final Map<String, BlockVote> votes = new LinkedHashMap<>();
    private volatile State state;

    public ConsensusRound(BlockProposal proposal, int threshold, long timeoutMs) {
        this.blockHash = proposal.getBlockHash();
        this.blockNumber = proposal.getBlockNumber();
        this.topicId = proposal.getTopicId();
        this.proposal = proposal;
        this.threshold = threshold;
        this.createdAtMs = System.currentTimeMillis();
        this.timeoutMs = timeoutMs;
        this.state = State.PROPOSED;
    }

    /**
     * Add a vote to the round.
     * @return true if this is a new vote (not a duplicate)
     */
    public boolean addVote(BlockVote vote) {
        if (state == State.FINALIZED || state == State.TIMED_OUT) {
            return false;
        }
        String keyHex = vote.signerKeyHex();
        if (votes.containsKey(keyHex)) {
            log.debug("Duplicate vote from {} for block #{}", keyHex, blockNumber);
            return false;
        }
        votes.put(keyHex, vote);
        state = State.VOTING;
        log.debug("Vote added from {} for block #{}: {}/{} votes",
                keyHex, blockNumber, votes.size(), threshold);
        return true;
    }

    /**
     * Check if the threshold of votes has been met.
     */
    public boolean isThresholdMet() {
        return votes.size() >= threshold;
    }

    /**
     * Check if the round has timed out.
     */
    public boolean isTimedOut() {
        return System.currentTimeMillis() - createdAtMs > timeoutMs;
    }

    /**
     * Build an aggregated ConsensusProof from collected votes.
     */
    public ConsensusProof buildAggregatedProof() {
        List<byte[]> signatures = new ArrayList<>();
        List<byte[]> signerKeys = new ArrayList<>();
        for (BlockVote vote : votes.values()) {
            signatures.add(vote.getSignature());
            signerKeys.add(vote.getSignerKey());
        }

        return ConsensusProof.builder()
                .mode(ConsensusMode.MULTI_SIG)
                .proposerKey(proposal.getProposerKey())
                .signatures(signatures)
                .signerKeys(signerKeys)
                .threshold(threshold)
                .build();
    }

    /**
     * Build the finalized AppBlock with the aggregated proof.
     */
    public AppBlock buildFinalizedBlock() {
        ConsensusProof proof = buildAggregatedProof();
        return AppBlock.builder()
                .blockNumber(proposal.getBlockNumber())
                .topicId(proposal.getTopicId())
                .timestamp(proposal.getTimestamp())
                .prevBlockHash(proposal.getPrevBlockHash())
                .stateHash(proposal.getStateHash())
                .blockHash(proposal.getBlockHash())
                .messages(proposal.getMessages())
                .consensusProof(proof)
                .build();
    }

    public void markFinalized() {
        this.state = State.FINALIZED;
    }

    public void markTimedOut() {
        this.state = State.TIMED_OUT;
    }

    public String blockHashHex() {
        return HexUtil.encodeHexString(blockHash);
    }
}
