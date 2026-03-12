package com.bloxbean.cardano.yaci.node.api.events;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.events.api.Event;

/**
 * Published when an app message has been sent to connected peers via Protocol 100.
 */
public final class AppMessagePropagatedEvent implements Event {
    private final AppMessage message;
    private final int peerCount;

    public AppMessagePropagatedEvent(AppMessage message, int peerCount) {
        this.message = message;
        this.peerCount = peerCount;
    }

    public AppMessage message() {
        return message;
    }

    public int peerCount() {
        return peerCount;
    }
}
