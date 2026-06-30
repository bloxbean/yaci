package com.bloxbean.cardano.yaci.core.network.server;

/**
 * Admission decision for an inbound node-to-node server connection.
 */
public record ServerConnectionDecision(boolean accepted, String reason) {
    public static ServerConnectionDecision accept() {
        return new ServerConnectionDecision(true, null);
    }

    public static ServerConnectionDecision reject(String reason) {
        return new ServerConnectionDecision(false, reason);
    }
}
