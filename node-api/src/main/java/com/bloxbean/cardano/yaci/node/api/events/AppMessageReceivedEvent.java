package com.bloxbean.cardano.yaci.node.api.events;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.events.api.Event;

/**
 * Published when an app message is received from a peer or local submission and authenticated.
 */
public final class AppMessageReceivedEvent implements Event {
    private final AppMessage message;
    private final String source;

    public AppMessageReceivedEvent(AppMessage message, String source) {
        this.message = message;
        this.source = source;
    }

    public AppMessage message() {
        return message;
    }

    public String source() {
        return source;
    }
}
