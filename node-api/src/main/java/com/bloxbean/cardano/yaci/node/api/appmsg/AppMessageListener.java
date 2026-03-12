package com.bloxbean.cardano.yaci.node.api.appmsg;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;

/**
 * High-level listener for app-layer message events.
 */
public interface AppMessageListener {

    /**
     * Called when a new app message is received (from peer or local submission) and authenticated.
     */
    default void onMessageReceived(AppMessage message, String source) {}

    /**
     * Called when an app message has been accepted (e.g., added to mempool).
     */
    default void onMessageAccepted(AppMessage message) {}
}
