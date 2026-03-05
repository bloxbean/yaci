package com.bloxbean.cardano.yaci.node.app.api.utxos.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigInteger;

/**
 * Amount DTO matching Yaci Store's response format.
 * Lovelace is represented as unit="lovelace", native assets as unit=policyId+assetName.
 */
public record AmountDto(
        String unit,
        @JsonProperty("policy_id") String policyId,
        @JsonProperty("asset_name") String assetName,
        BigInteger quantity
) {}
