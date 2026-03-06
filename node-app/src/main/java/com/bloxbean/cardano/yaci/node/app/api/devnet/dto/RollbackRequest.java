package com.bloxbean.cardano.yaci.node.app.api.devnet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RollbackRequest(
    @JsonProperty("slot") Long slot,
    @JsonProperty("block_number") Long blockNumber,
    @JsonProperty("count") Integer count
) {}
