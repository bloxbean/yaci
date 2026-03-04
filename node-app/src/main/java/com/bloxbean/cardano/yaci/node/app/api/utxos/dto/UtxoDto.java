package com.bloxbean.cardano.yaci.node.app.api.utxos.dto;

import com.bloxbean.cardano.yaci.node.api.utxo.model.AssetAmount;
import com.bloxbean.cardano.yaci.node.api.utxo.model.Outpoint;

import java.math.BigInteger;
import java.util.List;

/**
 * REST DTO for UTXO responses with hex-encoded byte fields.
 */
public record UtxoDto(
        Outpoint outpoint,
        String address,
        BigInteger lovelace,
        List<AssetAmount> assets,
        String datumHash,
        String inlineDatum, // hex string or null
        String referenceScriptHash,
        boolean collateralReturn,
        long slot,
        long blockNumber,
        String blockHash
) {}

