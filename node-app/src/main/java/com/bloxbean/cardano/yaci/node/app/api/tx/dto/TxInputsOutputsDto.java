package com.bloxbean.cardano.yaci.node.app.api.tx.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

/**
 * Top-level DTO for GET /api/v1/txs/{txHash}/utxos matching Yaci Store's TxInputsOutputs.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TxInputsOutputsDto(
        String hash,
        List<TxUtxoDto> inputs,
        List<TxUtxoDto> outputs
) {}
