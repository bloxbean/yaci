package com.bloxbean.cardano.yaci.node.api.utxo;

import com.bloxbean.cardano.yaci.node.api.utxo.model.MmrProof;
import com.bloxbean.cardano.yaci.node.api.utxo.model.Outpoint;

import java.util.Optional;

/**
 * Optional MMR state interface for UTXO proofs and commitments.
 */
public interface UtxoMmrState {
    /** Returns total number of MMR leaves appended so far. */
    long getMmrLeafCount();

    /** Returns the current bag-of-peaks root hash (hex). */
    String getMmrRootHex();

    /** Lookup the MMR leaf index for an outpoint if available. */
    Optional<Long> getLeafIndex(Outpoint outpoint);

    /** Get a proof for the given leaf index. */
    Optional<MmrProof> getProof(long leafIndex);
}

