package com.bloxbean.cardano.yaci.node.app.api.devnet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SnapshotResponse(
    @JsonProperty("name") String name,
    @JsonProperty("slot") long slot,
    @JsonProperty("block_number") long blockNumber,
    @JsonProperty("created_at") long createdAt
) {}
