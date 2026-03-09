package com.bloxbean.cardano.yaci.node.app.api.devnet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TimeAdvanceResponse(
    @JsonProperty("message") String message,
    @JsonProperty("new_slot") long newSlot,
    @JsonProperty("new_block_number") long newBlockNumber,
    @JsonProperty("blocks_produced") int blocksProduced
) {}
