package com.bloxbean.cardano.yaci.node.runtime.utxo;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.byron.ByronMainBlock;
import com.bloxbean.cardano.yaci.core.model.byron.ByronTx;
import com.bloxbean.cardano.yaci.core.model.byron.ByronTxOut;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class UtxoTxNormalizer {
    private UtxoTxNormalizer() {}

    static MultiEraBlockTxs fromShelley(Era era, long slot, long blockNumber, String blockHash, Block block) {
        if (block == null || block.getTransactionBodies() == null) return new MultiEraBlockTxs(era, slot, blockNumber, blockHash, List.of());
        List<TransactionBody> txs = block.getTransactionBodies();
        Set<Integer> invalidIdx = new HashSet<>();
        if (block.getInvalidTransactions() != null) invalidIdx.addAll(block.getInvalidTransactions());

        List<MultiEraTx> nTxs = new ArrayList<>(txs.size());
        for (int i = 0; i < txs.size(); i++) {
            TransactionBody tx = txs.get(i);
            boolean invalid = invalidIdx.contains(i);

            // inputs
            List<MultiEraInput> ins = new ArrayList<>();
            if (!invalid && tx.getInputs() != null) {
                for (var in : tx.getInputs()) ins.add(new MultiEraInput(in.getTransactionId(), in.getIndex()));
            }
            List<MultiEraInput> colIns = new ArrayList<>();
            if (invalid && tx.getCollateralInputs() != null) {
                for (var in : tx.getCollateralInputs()) colIns.add(new MultiEraInput(in.getTransactionId(), in.getIndex()));
            }

            // outputs (collect always to preserve original count; invalid txs won't use them for creation)
            List<MultiEraOutput> outs = new ArrayList<>();
            if (tx.getOutputs() != null) {
                for (int outIdx = 0; outIdx < tx.getOutputs().size(); outIdx++) {
                    var out = tx.getOutputs().get(outIdx);
                    BigInteger lovelace = BigInteger.ZERO;
                    List<com.bloxbean.cardano.yaci.core.model.Amount> assets = new ArrayList<>();
                    var amounts = out.getAmounts();
                    if (amounts != null) {
                        for (var a : amounts) {
                            if ("lovelace".equals(a.getUnit())) lovelace = a.getQuantity();
                            else assets.add(a);
                        }
                    }
                    String datumHash = out.getDatumHash();
                    byte[] inlineDatum = out.getInlineDatum() != null ? com.bloxbean.cardano.yaci.core.util.HexUtil.decodeHexString(out.getInlineDatum()) : null;
                    String scriptRefHex = out.getScriptRef();
                    outs.add(new MultiEraOutput(out.getAddress(), lovelace, assets, datumHash, inlineDatum, scriptRefHex, false));
                }
            }

            MultiEraOutput colRet = null;
            if (invalid && tx.getCollateralReturn() != null) {
                var out = tx.getCollateralReturn();
                BigInteger lovelace = BigInteger.ZERO;
                List<com.bloxbean.cardano.yaci.core.model.Amount> assets = new ArrayList<>();
                var amounts = out.getAmounts();
                if (amounts != null) {
                    for (var a : amounts) {
                        if ("lovelace".equals(a.getUnit())) lovelace = a.getQuantity();
                        else assets.add(a);
                    }
                }
                String datumHash = out.getDatumHash();
                byte[] inlineDatum = out.getInlineDatum() != null ? com.bloxbean.cardano.yaci.core.util.HexUtil.decodeHexString(out.getInlineDatum()) : null;
                String scriptRefHex = out.getScriptRef();
                colRet = new MultiEraOutput(out.getAddress(), lovelace, assets, datumHash, inlineDatum, scriptRefHex, true);
            }

            nTxs.add(new MultiEraTx(tx.getTxHash(), ins, outs, colIns, colRet, invalid));
        }

        return new MultiEraBlockTxs(era, slot, blockNumber, blockHash, nTxs);
    }

    static MultiEraBlockTxs fromByron(long slot, long blockNumber, String blockHash, ByronMainBlock byron) {
        if (byron == null || byron.getBody() == null || byron.getBody().getTxPayload() == null) return new MultiEraBlockTxs(Era.Byron, slot, blockNumber, blockHash, List.of());
        List<ByronTx> byronTxList = new ArrayList<>();
        byron.getBody().getTxPayload().forEach(p -> byronTxList.add(p.getTransaction()));

        List<MultiEraTx> nTxs = new ArrayList<>(byronTxList.size());
        for (ByronTx btx : byronTxList) {
            // inputs
            List<MultiEraInput> ins = new ArrayList<>();
            if (btx.getInputs() != null) {
                for (var in : btx.getInputs()) ins.add(new MultiEraInput(in.getTxId(), in.getIndex()));
            }
            // outputs (lovelace only)
            List<MultiEraOutput> outs = new ArrayList<>();
            if (btx.getOutputs() != null) {
                for (ByronTxOut o : btx.getOutputs()) {
                    String addr = o.getAddress() != null ? o.getAddress().getBase58Raw() : null;
                    BigInteger lovelace = o.getAmount() != null ? o.getAmount() : BigInteger.ZERO;
                    outs.add(new MultiEraOutput(addr, lovelace, List.of(), null, null, null, false));
                }
            }
            nTxs.add(new MultiEraTx(btx.getTxHash(), ins, outs, List.of(), null, false));
        }
        return new MultiEraBlockTxs(Era.Byron, slot, blockNumber, blockHash, nTxs);
    }
}
