package com.bloxbean.cardano.yaci.node.app.api.tx.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Single UTXO record matching Yaci Store's TxInputsOutputs inner format.
 */
public record TxUtxoDto(
        @JsonProperty("tx_hash") String txHash,
        @JsonProperty("output_index") int outputIndex,
        @JsonProperty("address") String address,
        @JsonProperty("amount") List<TxAmountDto> amount,
        @JsonProperty("data_hash") String dataHash,
        @JsonProperty("inline_datum") String inlineDatum,
        @JsonProperty("reference_script_hash") String referenceScriptHash
) {}
