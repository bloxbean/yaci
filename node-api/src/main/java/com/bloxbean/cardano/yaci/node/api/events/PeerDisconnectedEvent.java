package com.bloxbean.cardano.yaci.node.api.events;

import com.bloxbean.cardano.yaci.events.api.Event;
import com.bloxbean.cardano.yaci.node.api.config.PeerType;

/**
 * Published when a peer connection is lost.
 */
public final class PeerDisconnectedEvent implements Event {

    private final String peerId;
    private final PeerType peerType;
    private final DisconnectReason reason;

    public PeerDisconnectedEvent(String peerId, PeerType peerType, DisconnectReason reason) {
        this.peerId = peerId;
        this.peerType = peerType;
        this.reason = reason;
    }

    public String peerId() { return peerId; }
    public PeerType peerType() { return peerType; }
    public DisconnectReason reason() { return reason; }

    public enum DisconnectReason {
        TIMEOUT,
        ERROR,
        GRACEFUL,
        REMOVED
    }
}
