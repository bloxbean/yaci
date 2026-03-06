package com.bloxbean.cardano.yaci.node.app.api.tx.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Top-level DTO for GET /api/v1/txs/{txHash}/utxos matching Yaci Store's TxInputsOutputs.
 */
public record TxInputsOutputsDto(
        @JsonProperty("hash") String hash,
        @JsonProperty("inputs") List<TxUtxoDto> inputs,
        @JsonProperty("outputs") List<TxUtxoDto> outputs
) {}
