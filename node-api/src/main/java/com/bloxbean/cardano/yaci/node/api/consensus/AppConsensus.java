package com.bloxbean.cardano.yaci.node.api.consensus;

import com.bloxbean.cardano.yaci.node.api.ledger.AppBlock;

/**
 * Pluggable consensus interface for app-layer block finalization.
 * <p>
 * Implementations define how blocks are proposed and agreed upon:
 * <ul>
 *   <li>{@link ConsensusMode#SINGLE_SIGNER} — proposer finalizes immediately</li>
 *   <li>{@link ConsensusMode#MULTI_SIG} — requires n-of-m operator signatures</li>
 * </ul>
 */
public interface AppConsensus {

    /**
     * Whether this node can propose an app block at this time.
     * For SingleSigner, always true. For MultiSig, may depend on round/rotation.
     */
    boolean canPropose();

    /**
     * Create a consensus proof for a proposed app block.
     * For SingleSigner, signs the block hash with the node's key.
     * For MultiSig, signs and adds to the local proof (collection from peers is separate).
     *
     * @param block the proposed app block
     * @return consensus proof (may be partial for MultiSig until threshold met)
     */
    ConsensusProof createProof(AppBlock block);

    /**
     * Verify that a consensus proof is valid for the given block.
     *
     * @param block the app block
     * @param proof the consensus proof to verify
     * @return true if the proof is valid and meets the consensus threshold
     */
    boolean verifyProof(AppBlock block, ConsensusProof proof);

    /**
     * @return the consensus mode this implementation uses
     */
    ConsensusMode consensusMode();

    /**
     * @return the consensus parameters
     */
    ConsensusParams params();
}
