package com.bloxbean.cardano.yaci.node.ledgerrules;

import com.bloxbean.cardano.client.api.model.Utxo;

import java.util.List;
import java.util.Set;

/**
 * Evaluates Plutus scripts in a transaction and computes execution units (ExUnits).
 * Implementations use libraries like Scalus for actual script evaluation.
 */
public interface TransactionEvaluator {

    /**
     * Evaluate Plutus scripts and compute ExUnits per redeemer.
     *
     * @param txCbor     raw CBOR bytes of the transaction
     * @param inputUtxos pre-resolved input UTxOs (regular + reference + collateral)
     * @return list of evaluation results with tag, index, memory, and steps per redeemer
     * @throws Exception on evaluation failure
     */
    List<EvaluationResult> evaluate(byte[] txCbor, Set<Utxo> inputUtxos) throws Exception;

    /**
     * Result of evaluating a single redeemer.
     */
    record EvaluationResult(String tag, int index, long memory, long steps) {}
}
