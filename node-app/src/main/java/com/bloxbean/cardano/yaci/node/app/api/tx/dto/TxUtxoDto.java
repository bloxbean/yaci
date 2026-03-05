package com.bloxbean.cardano.yaci.node.app.api.tx.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

/**
 * Single UTXO record matching Yaci Store's TxInputsOutputs inner format.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TxUtxoDto(
        String txHash,
        int outputIndex,
        String address,
        List<TxAmountDto> amount,
        String dataHash,
        String inlineDatum,
        String referenceScriptHash
) {}
