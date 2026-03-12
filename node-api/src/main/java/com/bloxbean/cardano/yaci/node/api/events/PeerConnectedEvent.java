package com.bloxbean.cardano.yaci.node.api.events;

import com.bloxbean.cardano.yaci.events.api.Event;
import com.bloxbean.cardano.yaci.node.api.config.PeerType;

/**
 * Published when a peer completes handshake and becomes available for sync.
 */
public final class PeerConnectedEvent implements Event {

    private final String peerId;
    private final String host;
    private final int port;
    private final PeerType peerType;

    public PeerConnectedEvent(String peerId, String host, int port, PeerType peerType) {
        this.peerId = peerId;
        this.host = host;
        this.port = port;
        this.peerType = peerType;
    }

    public String peerId() { return peerId; }
    public String host() { return host; }
    public int port() { return port; }
    public PeerType peerType() { return peerType; }
}
