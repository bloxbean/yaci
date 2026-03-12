package com.bloxbean.cardano.yaci.node.api.ledger;

import com.bloxbean.cardano.yaci.node.api.consensus.ConsensusProof;

import java.util.List;
import java.util.Optional;

/**
 * Persistent storage for finalized app blocks.
 * Each topic maintains its own independent block sequence.
 */
public interface AppLedger {

    /**
     * Store a finalized app block.
     */
    void storeBlock(AppBlock block);

    /**
     * Retrieve an app block by topic and block number.
     */
    Optional<AppBlock> getBlock(String topicId, long blockNumber);

    /**
     * Get the latest (tip) block for a topic.
     */
    Optional<AppBlock> getLatestBlock(String topicId);

    /**
     * Get the tip metadata for a topic.
     */
    Optional<AppLedgerTip> getTip(String topicId);

    /**
     * Get blocks in a range for a topic (inclusive).
     */
    List<AppBlock> getBlocks(String topicId, long fromBlock, long toBlock);

    /**
     * Store a consensus proof for a block (may be updated after initial storage).
     */
    void storeConsensusProof(String topicId, long blockNumber, ConsensusProof proof);

    /**
     * Retrieve the consensus proof for a block.
     */
    Optional<ConsensusProof> getConsensusProof(String topicId, long blockNumber);

    /**
     * Close and release resources.
     */
    void close();
}
