package com.bloxbean.cardano.yaci.node.runtime.genesis;

import java.util.Map;

/**
 * Data extracted from a shelley-genesis.json file.
 *
 * @param initialFunds      hex-encoded address bytes → lovelace
 * @param networkMagic      protocol magic number
 * @param epochLength       number of slots per epoch
 * @param slotLength        slot duration in seconds
 * @param systemStart       ISO-8601 system start timestamp
 * @param maxLovelaceSupply max lovelace supply
 * @param activeSlotsCoeff  fraction of slots that are active (0.0–1.0)
 * @param securityParam     k value (finality confirmation depth)
 * @param maxKESEvolutions  max KES key evolutions
 * @param slotsPerKESPeriod slots per KES period
 * @param updateQuorum      governance update quorum
 */
public record ShelleyGenesisData(
        Map<String, Long> initialFunds,
        long networkMagic,
        long epochLength,
        double slotLength,
        String systemStart,
        long maxLovelaceSupply,
        double activeSlotsCoeff,
        long securityParam,
        long maxKESEvolutions,
        long slotsPerKESPeriod,
        long updateQuorum
) {}
