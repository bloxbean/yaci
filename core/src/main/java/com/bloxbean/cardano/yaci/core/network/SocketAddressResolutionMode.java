package com.bloxbean.cardano.yaci.core.network;

/**
 * Controls how TCP node clients resolve socket addresses during connection attempts.
 */
public enum SocketAddressResolutionMode {
    /**
     * Create a fresh {@code InetSocketAddress} for each connection attempt and let the JVM choose the address.
     */
    STANDARD,

    /**
     * Resolve all DNS answers for each connection attempt and rotate across the returned addresses.
     */
    DNS_ROTATING
}
