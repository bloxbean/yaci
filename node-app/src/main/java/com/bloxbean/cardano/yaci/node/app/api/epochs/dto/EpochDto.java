package com.bloxbean.cardano.yaci.node.app.api.epochs.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Epoch info DTO matching Yaci Store's response format.
 */
public record EpochDto(
        @JsonProperty("epoch") int epoch,
        @JsonProperty("start_time") long startTime,
        @JsonProperty("end_time") long endTime,
        @JsonProperty("first_block_time") long firstBlockTime,
        @JsonProperty("last_block_time") long lastBlockTime,
        @JsonProperty("block_count") long blockCount,
        @JsonProperty("tx_count") long txCount,
        @JsonProperty("output") String output,
        @JsonProperty("fees") String fees,
        @JsonProperty("active_stake") String activeStake
) {
    /**
     * Create a minimal epoch DTO with only the epoch number populated.
     */
    public static EpochDto ofEpoch(int epoch) {
        return new EpochDto(epoch, 0, 0, 0, 0, 0, 0, "0", "0", "0");
    }
}
