package com.bloxbean.cardano.yaci.node.runtime.events;

import com.bloxbean.cardano.yaci.events.api.Event;

public final class TipChangedEvent implements Event {
    private final Long previousSlot;
    private final Long previousBlockNo;
    private final String previousHash;
    private final long currentSlot;
    private final long currentBlockNo;
    private final String currentHash;

    public TipChangedEvent(Long previousSlot, Long previousBlockNo, String previousHash,
                           long currentSlot, long currentBlockNo, String currentHash) {
        this.previousSlot = previousSlot;
        this.previousBlockNo = previousBlockNo;
        this.previousHash = previousHash;
        this.currentSlot = currentSlot;
        this.currentBlockNo = currentBlockNo;
        this.currentHash = currentHash;
    }

    public Long previousSlot() { return previousSlot; }
    public Long previousBlockNo() { return previousBlockNo; }
    public String previousHash() { return previousHash; }
    public long currentSlot() { return currentSlot; }
    public long currentBlockNo() { return currentBlockNo; }
    public String currentHash() { return currentHash; }
}

