package com.bloxbean.cardano.yaci.node.app.api.utxos.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * UTXO DTO matching Yaci Store's response format.
 */
public record UtxoDto(
        @JsonProperty("tx_hash") String txHash,
        @JsonProperty("output_index") int outputIndex,
        @JsonProperty("address") String address,
        @JsonProperty("amount") List<AmountDto> amount,
        @JsonProperty("data_hash") String dataHash,
        @JsonProperty("inline_datum") String inlineDatum,
        @JsonProperty("reference_script_hash") String referenceScriptHash,
        @JsonProperty("epoch") int epoch,
        @JsonProperty("block_number") long blockNumber,
        @JsonProperty("block_time") long blockTime
) {}
