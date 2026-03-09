package com.bloxbean.cardano.yaci.node.scalusbridge;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.yaci.node.ledgerrules.TransactionEvaluator;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Scalus-based implementation of {@link TransactionEvaluator}.
 * Evaluates Plutus scripts and computes ExUnits using Scalus EvaluateAndComputeCost mode.
 */
public class ScalusBasedTransactionEvaluator implements TransactionEvaluator {

    private final ProtocolParams protocolParams;
    private final SlotConfigHandle slotConfig;
    private final int networkId;

    ScalusBasedTransactionEvaluator(ProtocolParams protocolParams, SlotConfigHandle slotConfig, int networkId) {
        this.protocolParams = protocolParams;
        this.slotConfig = slotConfig;
        this.networkId = networkId;
    }

    @Override
    public List<EvaluationResult> evaluate(byte[] txCbor, Set<Utxo> inputUtxos) throws Exception {
        long currentSlot = 0;
        try {
            var tx = com.bloxbean.cardano.client.transaction.spec.Transaction.deserialize(txCbor);
            if (tx.getBody().getValidityStartInterval() > 0) {
                currentSlot = tx.getBody().getValidityStartInterval();
            }
        } catch (Exception e) {
            // Use 0 if extraction fails
        }

        SlotConfigHandle sc = slotConfig != null ? slotConfig : SlotConfigBridge.preview();

        List<EvaluationEntry> entries = LedgerBridge.evaluate(
                txCbor, protocolParams, inputUtxos, currentSlot, sc, networkId);

        return entries.stream()
                .map(e -> new EvaluationResult(e.tag(), e.index(), e.memory(), e.steps()))
                .collect(Collectors.toList());
    }
}
