package com.bloxbean.cardano.yaci.node.app.api.blocks.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Block DTO matching Yaci Store's BlockDto response format.
 */
public record BlockDto(
        @JsonProperty("time") long time,
        @JsonProperty("height") long height,
        @JsonProperty("number") long number,
        @JsonProperty("hash") String hash,
        @JsonProperty("slot") long slot,
        @JsonProperty("epoch") int epoch,
        @JsonProperty("epoch_slot") int epochSlot,
        @JsonProperty("slot_leader") String slotLeader,
        @JsonProperty("size") long size,
        @JsonProperty("tx_count") int txCount,
        @JsonProperty("output") String output,
        @JsonProperty("fees") String fees,
        @JsonProperty("previous_block") String previousBlock,
        @JsonProperty("next_block") String nextBlock,
        @JsonProperty("confirmations") int confirmations,
        @JsonProperty("era") int era
) {}
