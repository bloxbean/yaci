package com.bloxbean.cardano.yaci.node.api.events;

import com.bloxbean.cardano.yaci.events.api.Event;

public record BlockProducedEvent(
        int era,
        long slot,
        long blockNumber,
        byte[] blockHash,
        int txCount
) implements Event {}
