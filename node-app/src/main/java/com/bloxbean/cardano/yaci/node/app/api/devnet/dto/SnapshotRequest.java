package com.bloxbean.cardano.yaci.node.app.api.devnet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SnapshotRequest(
    @JsonProperty("name") String name
) {}
