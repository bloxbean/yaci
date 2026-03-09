package com.bloxbean.cardano.yaci.node.app.api.utxos.dto;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.node.api.utxo.model.AssetAmount;
import com.bloxbean.cardano.yaci.node.api.utxo.model.Utxo;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongUnaryOperator;
import java.util.stream.Collectors;

public final class UtxoDtoMapper {
    private UtxoDtoMapper() {}

    public static UtxoDto toDto(Utxo u) {
        return toDto(u, slot -> 0);
    }

    public static UtxoDto toDto(Utxo u, LongUnaryOperator slotToTime) {
        String inlineHex = u.inlineDatum() != null ? HexUtil.encodeHexString(u.inlineDatum()) : null;

        List<AmountDto> amounts = new ArrayList<>();
        // Lovelace as first entry
        amounts.add(new AmountDto("lovelace", "", "lovelace", u.lovelace()));
        // Native assets
        if (u.assets() != null) {
            for (AssetAmount a : u.assets()) {
                String unit = a.policyId() + a.assetName();
                amounts.add(new AmountDto(unit, a.policyId(), a.assetName(), a.quantity()));
            }
        }

        return new UtxoDto(
                u.outpoint().txHash(),
                u.outpoint().index(),
                u.address(),
                amounts,
                u.datumHash(),
                inlineHex,
                u.referenceScriptHash(),
                0, // epoch (not tracked per-utxo)
                u.blockNumber(),
                slotToTime.applyAsLong(u.slot())
        );
    }

    public static List<UtxoDto> toDtoList(List<Utxo> list) {
        return toDtoList(list, slot -> 0);
    }

    public static List<UtxoDto> toDtoList(List<Utxo> list, LongUnaryOperator slotToTime) {
        return list == null ? List.of() : list.stream().map(u -> toDto(u, slotToTime)).collect(Collectors.toList());
    }
}
