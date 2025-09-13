package com.bloxbean.cardano.yaci.node.runtime.events;

import com.bloxbean.cardano.yaci.core.model.byron.ByronEbBlock;
import com.bloxbean.cardano.yaci.events.api.Event;

public final class ByronEbBlockAppliedEvent implements Event {
    private final long slot;
    private final long blockNumber;
    private final String blockHash;
    private final ByronEbBlock block;

    public ByronEbBlockAppliedEvent(long slot, long blockNumber, String blockHash, ByronEbBlock block) {
        this.slot = slot;
        this.blockNumber = blockNumber;
        this.blockHash = blockHash;
        this.block = block;
    }

    public long slot() { return slot; }
    public long blockNumber() { return blockNumber; }
    public String blockHash() { return blockHash; }
    public ByronEbBlock block() { return block; }
}

