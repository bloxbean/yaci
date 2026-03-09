package com.bloxbean.cardano.yaci.node.runtime.blockproducer;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.node.api.utxo.model.AssetAmount;
import com.bloxbean.cardano.yaci.node.api.utxo.model.Utxo;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts yaci {@link com.bloxbean.cardano.yaci.node.api.utxo.model.Utxo} to
 * CCL {@link com.bloxbean.cardano.client.api.model.Utxo}.
 */
public class UtxoMapper {

    /**
     * Convert a yaci Utxo record to a CCL Utxo model.
     */
    public static com.bloxbean.cardano.client.api.model.Utxo toCclUtxo(Utxo yaciUtxo) {
        if (yaciUtxo == null) return null;

        List<Amount> amounts = new ArrayList<>();

        // Lovelace
        amounts.add(Amount.lovelace(yaciUtxo.lovelace()));

        // Multi-assets
        if (yaciUtxo.assets() != null) {
            for (AssetAmount asset : yaciUtxo.assets()) {
                String unit = asset.policyId() + asset.assetName();
                amounts.add(Amount.asset(unit, asset.quantity()));
            }
        }

        // Inline datum: byte[] -> hex string
        String inlineDatum = null;
        if (yaciUtxo.inlineDatum() != null) {
            inlineDatum = HexUtil.encodeHexString(yaciUtxo.inlineDatum());
        }

        return com.bloxbean.cardano.client.api.model.Utxo.builder()
                .txHash(yaciUtxo.outpoint().txHash())
                .outputIndex(yaciUtxo.outpoint().index())
                .address(yaciUtxo.address())
                .amount(amounts)
                .dataHash(yaciUtxo.datumHash())
                .inlineDatum(inlineDatum)
                .referenceScriptHash(yaciUtxo.referenceScriptHash())
                .build();
    }
}
