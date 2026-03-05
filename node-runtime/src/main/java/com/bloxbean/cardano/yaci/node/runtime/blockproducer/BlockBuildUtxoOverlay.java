package com.bloxbean.cardano.yaci.node.runtime.blockproducer;

import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.yaci.node.api.utxo.UtxoState;
import com.bloxbean.cardano.yaci.node.api.utxo.model.Outpoint;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

/**
 * Tracks UTXOs consumed during a single block-build cycle to detect intra-block double-spends.
 * Wraps the persistent {@link UtxoState} with an in-memory set of spent outpoints.
 */
public class BlockBuildUtxoOverlay {

    private final UtxoState utxoState;
    private final Set<Outpoint> spent = new HashSet<>();

    public BlockBuildUtxoOverlay(UtxoState utxoState) {
        this.utxoState = utxoState;
    }

    /**
     * Returns a resolver function that checks spent-tracking before delegating to UtxoState.
     * Returns null for already-spent outpoints.
     */
    public Function<Outpoint, Utxo> resolver() {
        return outpoint -> {
            if (spent.contains(outpoint)) {
                return null; // Already consumed in this block
            }
            return utxoState.getUtxo(outpoint)
                    .map(UtxoMapper::toCclUtxo)
                    .orElse(null);
        };
    }

    /**
     * Mark the regular inputs of a transaction as spent for subsequent validations.
     */
    public void markSpent(byte[] txCbor) {
        try {
            Transaction tx = Transaction.deserialize(txCbor);
            if (tx.getBody().getInputs() != null) {
                for (TransactionInput input : tx.getBody().getInputs()) {
                    spent.add(new Outpoint(input.getTransactionId(), input.getIndex()));
                }
            }
        } catch (Exception e) {
            // If deserialization fails here, the tx already passed validation,
            // so this shouldn't happen. Log and continue.
        }
    }

    /**
     * Reset the spent tracking for the next block cycle.
     */
    public void reset() {
        spent.clear();
    }
}
