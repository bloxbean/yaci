package com.bloxbean.cardano.yaci.node.runtime.events;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.events.api.Event;

/**
 * Event published when a block is received from the upstream Cardano node.
 * 
 * This event is published BEFORE the block is stored in the chain state,
 * allowing plugins to inspect or validate blocks before persistence.
 * For post-storage processing, use BlockAppliedEvent instead.
 * 
 * Event timing:
 * - Published immediately after block is fetched and parsed
 * - Before any validation or storage operations
 * - May be published for blocks that are later rejected
 * 
 * Use cases:
 * - Block validation and filtering
 * - Pre-storage transformations
 * - Network monitoring and statistics
 * - Real-time block streaming to external systems
 * 
 * Note: The block reference is to the in-memory parsed object.
 * For Byron era blocks, the block field may be null as they use
 * a different internal representation.
 * 
 * @see BlockAppliedEvent for post-storage notifications
 */
public final class BlockReceivedEvent implements Event {
    private final Era era;
    private final long slot;
    private final long blockNumber;
    private final String blockHash;
    private final Block block; // reference to parsed block (may be null for Byron)

    public BlockReceivedEvent(Era era, long slot, long blockNumber, String blockHash, Block block) {
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

