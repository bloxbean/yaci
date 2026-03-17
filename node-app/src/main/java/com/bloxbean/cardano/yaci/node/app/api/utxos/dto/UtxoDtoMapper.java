package com.bloxbean.cardano.yaci.node.app.api.utxos.dto;

import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.node.api.utxo.model.AssetAmount;
import com.bloxbean.cardano.yaci.node.api.utxo.model.Utxo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongUnaryOperator;
import java.util.stream.Collectors;

import static com.bloxbean.cardano.yaci.core.util.Constants.LOVELACE;

public final class UtxoDtoMapper {
    private static final Logger log = LoggerFactory.getLogger(UtxoDtoMapper.class);
    private UtxoDtoMapper() {}

    public static UtxoDto toDto(Utxo u) {
        return toDto(u, slot -> 0);
    }

    public static UtxoDto toDto(Utxo u, LongUnaryOperator slotToTime) {
        String inlineHex = u.inlineDatum() != null ? HexUtil.encodeHexString(u.inlineDatum()) : null;
        String dataHash = u.datumHash();
        if (dataHash == null && u.inlineDatum() != null) {
            try {
                dataHash = PlutusData.deserialize(HexUtil.decodeHexString(inlineHex)).getDatumHash();
            } catch (Exception e) {
                log.warn("Failed to derive datum hash from inline datum", e);
            }
        }

        List<AmountDto> amounts = new ArrayList<>();
        // Lovelace as first entry
        amounts.add(new AmountDto(LOVELACE, u.lovelace()));
        // Native assets
        if (u.assets() != null) {
            for (AssetAmount a : u.assets()) {
                String unit = a.policyId() + a.assetName();
                amounts.add(new AmountDto(unit, a.quantity()));
            }
        }

        return new UtxoDto(
                u.outpoint().txHash(),
                u.outpoint().index(),
                u.address(),
                amounts,
                dataHash,
                inlineHex,
                u.scriptRef(),
                u.referenceScriptHash(),
                u.blockHash()
        );
    }

    public static List<UtxoDto> toDtoList(List<Utxo> list) {
        return toDtoList(list, slot -> 0);
    }

    public static List<UtxoDto> toDtoList(List<Utxo> list, LongUnaryOperator slotToTime) {
        return list == null ? List.of() : list.stream().map(u -> toDto(u, slotToTime)).collect(Collectors.toList());
    }
}
