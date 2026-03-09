package com.bloxbean.cardano.yaci.node.api.model;

/**
 * Result of a time/slot advance operation.
 */
public record TimeAdvanceResult(
    long newSlot,
    long newBlockNumber,
    int blocksProduced
) {}
