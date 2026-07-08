package com.bloxbean.cardano.yaci.core.protocol.appmsg.model;

import lombok.Getter;

/**
 * Authentication scheme carried in the app message envelope's auth-proof.
 * The transport only carries the scheme + proof; verification is performed
 * by the embedding node (e.g. Yano's app-chain admission layer) via
 * {@link com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgValidator}.
 */
@Getter
public enum AuthScheme {
    /** Ed25519 signature by {@code sender} over the signed body. */
    ED25519(0),
    /** CIP-137 compatible: cbor([kesSignature, opCert, coldVk]). Reserved, not yet implemented. */
    SPO_KES(1);

    private final int value;

    AuthScheme(int value) {
        this.value = value;
    }

    public static AuthScheme fromValue(int value) {
        for (AuthScheme scheme : values()) {
            if (scheme.value == value) return scheme;
        }
        throw new IllegalArgumentException("Unknown auth scheme: " + value);
    }
}
