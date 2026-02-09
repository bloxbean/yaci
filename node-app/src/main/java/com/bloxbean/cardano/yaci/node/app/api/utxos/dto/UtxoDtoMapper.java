package com.bloxbean.cardano.yaci.node.app.api.utxos.dto;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.node.api.utxo.model.Utxo;

import java.util.List;
import java.util.stream.Collectors;

public final class UtxoDtoMapper {
    private UtxoDtoMapper() {}

    public static UtxoDto toDto(Utxo u) {
        String inlineHex = u.inlineDatum() != null ? HexUtil.encodeHexString(u.inlineDatum()) : null;
        return new UtxoDto(
                u.outpoint(),
                u.address(),
                u.lovelace(),
                u.assets(),
                u.datumHash(),
                inlineHex,
                u.referenceScriptHash(),
                u.collateralReturn(),
                u.slot(),
                u.blockNumber(),
                u.blockHash()
        );
    }

    public static List<UtxoDto> toDtoList(List<Utxo> list) {
        return list == null ? List.of() : list.stream().map(UtxoDtoMapper::toDto).collect(Collectors.toList());
    }
}

