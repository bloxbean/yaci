package com.bloxbean.cardano.yaci.node.api.events;

import com.bloxbean.cardano.yaci.events.api.Event;

/**
 * Published after an app block has been finalized and stored in the app ledger.
 */
public final class AppBlockProducedEvent implements Event {

    private final String topicId;
    private final long blockNumber;
    private final byte[] blockHash;
    private final int messageCount;
    private final long timestamp;

    public AppBlockProducedEvent(String topicId, long blockNumber, byte[] blockHash,
                                 int messageCount, long timestamp) {
        this.topicId = topicId;
        this.blockNumber = blockNumber;
        this.blockHash = blockHash;
        this.messageCount = messageCount;
        this.timestamp = timestamp;
    }

    public String topicId() { return topicId; }
    public long blockNumber() { return blockNumber; }
    public byte[] blockHash() { return blockHash; }
    public int messageCount() { return messageCount; }
    public long timestamp() { return timestamp; }
}
