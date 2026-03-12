package com.bloxbean.cardano.yaci.node.api.config;

/**
 * Type of upstream peer connection.
 */
public enum PeerType {
    /** Standard Cardano relay node (L1 block sync only) */
    CARDANO,
    /** Yaci node (L1 block sync + future app-layer protocols) */
    YACI
}
