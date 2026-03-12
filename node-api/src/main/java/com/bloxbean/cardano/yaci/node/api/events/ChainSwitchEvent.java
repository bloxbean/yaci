package com.bloxbean.cardano.yaci.node.api.events;

import com.bloxbean.cardano.yaci.events.api.Event;

/**
 * Published when chain selection switches to a different peer's chain.
 * This indicates a change in which peer we are following for block sync.
 */
public final class ChainSwitchEvent implements Event {

    private final String previousPeerId;
    private final long previousBlockNumber;
    private final long previousSlot;
    private final String newPeerId;
    private final long newBlockNumber;
    private final long newSlot;
    private final long rollbackDepth;

    public ChainSwitchEvent(String previousPeerId, long previousBlockNumber, long previousSlot,
                            String newPeerId, long newBlockNumber, long newSlot,
                            long rollbackDepth) {
        this.previousPeerId = previousPeerId;
        this.previousBlockNumber = previousBlockNumber;
        this.previousSlot = previousSlot;
        this.newPeerId = newPeerId;
        this.newBlockNumber = newBlockNumber;
        this.newSlot = newSlot;
        this.rollbackDepth = rollbackDepth;
    }

    public String previousPeerId() { return previousPeerId; }
    public long previousBlockNumber() { return previousBlockNumber; }
    public long previousSlot() { return previousSlot; }
    public String newPeerId() { return newPeerId; }
    public long newBlockNumber() { return newBlockNumber; }
    public long newSlot() { return newSlot; }
    /** Number of blocks rolled back to reach the intersection point */
    public long rollbackDepth() { return rollbackDepth; }
}
