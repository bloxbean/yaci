package com.bloxbean.cardano.yaci.node.runtime.blockproducer;

import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.yaci.node.api.utxo.UtxoState;
import com.bloxbean.cardano.yaci.node.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yaci.node.ledgerrules.TransactionEvaluator;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * Service that resolves UTxOs and delegates to {@link TransactionEvaluator}
 * for Plutus script evaluation (ExUnits computation).
 */
@Slf4j
public class TransactionEvaluationService {

    private final TransactionEvaluator evaluator;
    private final UtxoState utxoState;

    public TransactionEvaluationService(TransactionEvaluator evaluator, UtxoState utxoState) {
        this.evaluator = evaluator;
        this.utxoState = utxoState;
    }

    /**
     * Evaluate Plutus scripts in the given transaction.
     * Resolves all inputs (regular + reference + collateral) from UtxoState.
     *
     * @param txCbor raw CBOR bytes of the transaction
     * @return evaluation results per redeemer
     * @throws Exception on deserialization, UTxO resolution, or evaluation failure
     */
    public List<TransactionEvaluator.EvaluationResult> evaluate(byte[] txCbor) throws Exception {
        Transaction transaction = Transaction.deserialize(txCbor);

        // Collect all inputs: regular + reference + collateral
        Set<Utxo> inputUtxos = new HashSet<>();
        List<TransactionInput> allInputs = new ArrayList<>();

        if (transaction.getBody().getInputs() != null) {
            allInputs.addAll(transaction.getBody().getInputs());
        }
        if (transaction.getBody().getReferenceInputs() != null) {
            allInputs.addAll(transaction.getBody().getReferenceInputs());
        }
        if (transaction.getBody().getCollateral() != null) {
            allInputs.addAll(transaction.getBody().getCollateral());
        }

        for (TransactionInput input : allInputs) {
            Outpoint op = new Outpoint(input.getTransactionId(), input.getIndex());
            var yaciUtxo = utxoState.getUtxo(op).orElse(null);
            if (yaciUtxo == null) {
                throw new IllegalArgumentException("UTXO not found: " + op.txHash() + "#" + op.index());
            }

            inputUtxos.add(UtxoMapper.toCclUtxo(yaciUtxo));
        }

        return evaluator.evaluate(txCbor, inputUtxos);
    }
}
