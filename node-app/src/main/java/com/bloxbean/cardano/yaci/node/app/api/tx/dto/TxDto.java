package com.bloxbean.cardano.yaci.node.app.api.tx.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Blockfrost-compatible transaction info DTO.
 */
public record TxDto(
        @JsonProperty("hash") String hash,
        @JsonProperty("block") String block,
        @JsonProperty("block_height") long blockHeight,
        @JsonProperty("block_time") long blockTime,
        @JsonProperty("slot") long slot,
        @JsonProperty("index") int index,
        @JsonProperty("output_amount") List<TxAmountDto> outputAmount,
        @JsonProperty("fees") String fees,
        @JsonProperty("size") int size,
        @JsonProperty("invalid_before") String invalidBefore,
        @JsonProperty("invalid_hereafter") String invalidHereafter,
        @JsonProperty("utxo_count") int utxoCount,
        @JsonProperty("valid_contract") boolean validContract
) {}
