package com.bloxbean.cardano.yaci.node.app.api;

import com.bloxbean.cardano.yaci.node.api.config.NodeConfig;

/**
 * Shared epoch/slot calculation utilities for REST endpoints.
 * Uses epoch length from NodeConfig (populated from shelley-genesis.json).
 */
public final class EpochUtil {
    private EpochUtil() {}

    public static long slotsPerEpoch(NodeConfig config) {
        return config.getEpochLength();
    }

    public static int slotToEpoch(long slot, NodeConfig config) {
        return (int) (slot / config.getEpochLength());
    }

    public static int slotToEpochSlot(long slot, NodeConfig config) {
        return (int) (slot % config.getEpochLength());
    }
}
