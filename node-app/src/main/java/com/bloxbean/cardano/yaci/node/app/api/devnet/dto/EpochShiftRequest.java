package com.bloxbean.cardano.yaci.node.app.api.devnet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record EpochShiftRequest(
    @JsonProperty("epochs") int epochs
) {}
