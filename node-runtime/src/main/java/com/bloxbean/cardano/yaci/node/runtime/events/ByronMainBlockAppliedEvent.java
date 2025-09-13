package com.bloxbean.cardano.yaci.node.runtime.events;

import com.bloxbean.cardano.yaci.core.model.byron.ByronMainBlock;
import com.bloxbean.cardano.yaci.events.api.Event;

public final class ByronMainBlockAppliedEvent implements Event {
    private final long slot;
    private final long blockNumber;
    private final String blockHash;
    private final ByronMainBlock block;

    public ByronMainBlockAppliedEvent(long slot, long blockNumber, String blockHash, ByronMainBlock block) {
        this.slot = slot;
        this.blockNumber = blockNumber;
        this.blockHash = blockHash;
        this.block = block;
    }

    public long slot() { return slot; }
    public long blockNumber() { return blockNumber; }
    public String blockHash() { return blockHash; }
    public ByronMainBlock block() { return block; }
}

