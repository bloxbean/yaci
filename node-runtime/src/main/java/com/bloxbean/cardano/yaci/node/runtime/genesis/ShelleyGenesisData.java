package com.bloxbean.cardano.yaci.node.runtime.genesis;

import java.util.Map;

/**
 * Data extracted from a shelley-genesis.json file.
 *
 * @param initialFunds    hex-encoded address bytes → lovelace
 * @param networkMagic    protocol magic number
 * @param epochLength     number of slots per epoch
 * @param slotLength      slot duration in seconds
 * @param systemStart     ISO-8601 system start timestamp
 * @param maxLovelaceSupply max lovelace supply
 */
public record ShelleyGenesisData(
        Map<String, Long> initialFunds,
        long networkMagic,
        long epochLength,
        double slotLength,
        String systemStart,
        long maxLovelaceSupply
) {}
