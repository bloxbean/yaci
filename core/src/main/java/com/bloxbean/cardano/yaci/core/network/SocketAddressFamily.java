package com.bloxbean.cardano.yaci.core.network;

/**
 * Controls address-family filtering and ordering for DNS resolved socket addresses.
 */
public enum SocketAddressFamily {
    ANY,
    IPV4_ONLY,
    IPV6_ONLY,
    IPV4_PREFERRED,
    IPV6_PREFERRED
}
