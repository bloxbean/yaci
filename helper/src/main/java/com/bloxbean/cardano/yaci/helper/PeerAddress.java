package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.protocol.Agent;

import java.net.InetSocketAddress;

/**
 * Where a side connection dials: the address the main connection is connected to, so that the EB and parent lookups
 * reach the relay that served the certifying block even when the host name resolves to several relays (ADR 0016 D4a).
 */
record PeerAddress(String host, int port) {

    /** The remote address of {@code agent}'s connection while it is open, else null. */
    static PeerAddress connected(Agent<?> agent) {
        if (agent != null && agent.getRemoteAddress() instanceof InetSocketAddress remote && remote.getAddress() != null)
            return new PeerAddress(remote.getAddress().getHostAddress(), remote.getPort());
        return null;
    }

    /**
     * The main connection's address, remembered across the gaps while it reconnects: the address it is connected to,
     * else the last one it was connected to, else the configured host and port (before its first connection). A gap
     * therefore never replaces a side connection, and a reconnect to the same relay does not either.
     */
    static final class Tracker {
        private final String host;
        private final int port;
        private volatile PeerAddress last;

        Tracker(String host, int port) {
            this.host = host;
            this.port = port;
        }

        PeerAddress current(Agent<?> agent) {
            PeerAddress now = connected(agent);
            if (now != null)
                last = now;
            PeerAddress known = last;
            return known != null ? known : new PeerAddress(host, port);
        }
    }
}
