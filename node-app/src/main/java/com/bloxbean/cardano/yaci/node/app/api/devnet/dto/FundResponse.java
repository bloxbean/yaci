package com.bloxbean.cardano.yaci.node.app.api.devnet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record FundResponse(
    @JsonProperty("tx_hash") String txHash,
    @JsonProperty("index") int index,
    @JsonProperty("lovelace") long lovelace
) {}
