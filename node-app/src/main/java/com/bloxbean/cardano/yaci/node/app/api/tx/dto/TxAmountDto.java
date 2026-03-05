package com.bloxbean.cardano.yaci.node.app.api.tx.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Amount DTO matching Yaci Store's TxInputsOutputs response format.
 * Quantity is String to match yaci-store convention.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TxAmountDto(
        String unit,
        String quantity
) {}
