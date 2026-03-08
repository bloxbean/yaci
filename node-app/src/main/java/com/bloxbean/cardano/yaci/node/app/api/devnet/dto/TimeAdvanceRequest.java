package com.bloxbean.cardano.yaci.node.app.api.devnet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TimeAdvanceRequest(
    @JsonProperty("slots") Integer slots,
    @JsonProperty("seconds") Integer seconds
) {}
