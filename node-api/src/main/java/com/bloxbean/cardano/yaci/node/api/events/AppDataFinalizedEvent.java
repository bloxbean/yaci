package com.bloxbean.cardano.yaci.node.api.events;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.events.api.Event;

/**
 * Published for each app message after it has been included in a finalized app block.
 */
public final class AppDataFinalizedEvent implements Event {

    private final AppMessage message;
    private final String topicId;
    private final long blockNumber;
    private final byte[] blockHash;

    public AppDataFinalizedEvent(AppMessage message, String topicId, long blockNumber, byte[] blockHash) {
        this.message = message;
        this.topicId = topicId;
        this.blockNumber = blockNumber;
        this.blockHash = blockHash;
    }

    public AppMessage message() { return message; }
    public String topicId() { return topicId; }
    public long blockNumber() { return blockNumber; }
    public byte[] blockHash() { return blockHash; }
}
