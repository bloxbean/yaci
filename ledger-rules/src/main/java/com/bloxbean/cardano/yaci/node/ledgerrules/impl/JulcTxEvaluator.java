package com.bloxbean.cardano.yaci.node.ledgerrules.impl;

import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.ScriptSupplier;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Utxo;

import com.bloxbean.cardano.julc.clientlib.eval.JulcTransactionEvaluator;
import com.bloxbean.cardano.julc.clientlib.eval.SlotConfig;
import com.bloxbean.cardano.yaci.node.ledgerrules.TransactionEvaluator;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class JulcTxEvaluator implements TransactionEvaluator {
    private final ProtocolParamsSupplier protocolParamsSupplier;
    private final ScriptSupplier scriptSupplier;
    private final com.bloxbean.cardano.julc.clientlib.eval.SlotConfig slotConfig;

    public JulcTxEvaluator(ProtocolParamsSupplier protocolParamsSupplier,
                           ScriptSupplier scriptSupplier, com.bloxbean.cardano.client.common.model.SlotConfig slotConfig) {
        this.protocolParamsSupplier = protocolParamsSupplier;
        this.scriptSupplier = scriptSupplier;
        this.slotConfig =
                new SlotConfig(slotConfig.getZeroSlot(), slotConfig.getZeroTime(), slotConfig.getSlotLength());
    }

    @Override
    public List<EvaluationResult> evaluate(byte[] txCbor, Set<Utxo> inputUtxos) throws Exception {
        UtxoSupplier utxoSupplier = new UtxoSupplier() {
            @Override
            public List<Utxo> getPage(String address, Integer nrOfItems, Integer page, OrderEnum order) {
                return List.of();
            }

            @Override
            public Optional<Utxo> getTxOutput(String txHash, int index) {
                return inputUtxos.stream()
                        .filter(u -> u.getTxHash().equals(txHash) && u.getOutputIndex() == index)
                        .findFirst();
            }
        };

        var txEvaluator = new JulcTransactionEvaluator(utxoSupplier, protocolParamsSupplier, scriptSupplier, slotConfig);

        var evaluationResult =  txEvaluator.evaluateTx(txCbor, inputUtxos);

        if (!evaluationResult.isSuccessful()) {
            throw new Exception("Script evaluation failed: " + evaluationResult.getResponse());
        }

        return evaluationResult.getValue().stream()
                .map(er -> new EvaluationResult(
                        er.getRedeemerTag().name().toLowerCase(),
                        er.getIndex(),
                        er.getExUnits().getMem().longValueExact(),
                        er.getExUnits().getSteps().longValueExact()))
                .collect(Collectors.toList());
    }
}
