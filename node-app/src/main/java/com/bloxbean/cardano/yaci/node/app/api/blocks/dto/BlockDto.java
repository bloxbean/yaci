package com.bloxbean.cardano.yaci.node.app.api.blocks.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Block DTO matching Yaci Store's BlockDto response format.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record BlockDto(
        long time,
        long height,
        long number,
        String hash,
        long slot,
        int epoch,
        int epochSlot,
        String slotLeader,
        long size,
        int txCount,
        String output,
        String fees,
        String previousBlock,
        String nextBlock,
        int confirmations,
        int era
) {}
