package com.bloxbean.cardano.yaci.node.api.model;

/**
 * Genesis parameters extracted from shelley-genesis.json.
 * Used by the /genesis REST endpoint.
 */
public record GenesisParameters(
        double activeSlotsCoefficient,
        long updateQuorum,
        String maxLovelaceSupply,
        long networkMagic,
        long epochLength,
        String systemStart,
        long slotsPerKesPeriod,
        int slotLength,
        long maxKesEvolutions,
        long securityParam
) {}
