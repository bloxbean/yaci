package com.bloxbean.cardano.yaci.node.runtime.events;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.events.api.Event;

public final class BlockAppliedEvent implements Event {
    private final Era era;
    private final long slot;
    private final long blockNumber;
    private final String blockHash;
    private final Block block; // reference to parsed block

    public BlockAppliedEvent(Era era, long slot, long blockNumber, String blockHash, Block block) {
        this.era = era;
        this.slot = slot;
        this.blockNumber = blockNumber;
        this.blockHash = blockHash;
        this.block = block;
    }

    public Era era() { return era; }
    public long slot() { return slot; }
    public long blockNumber() { return blockNumber; }
    public String blockHash() { return blockHash; }
    public Block block() { return block; }
}

