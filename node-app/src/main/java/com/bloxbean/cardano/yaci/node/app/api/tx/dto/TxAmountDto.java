package com.bloxbean.cardano.yaci.node.app.api.tx.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Amount DTO matching Yaci Store's TxInputsOutputs response format.
 * Quantity is String to match yaci-store convention.
 */
public record TxAmountDto(
        @JsonProperty("unit") String unit,
        @JsonProperty("quantity") String quantity
) {}
