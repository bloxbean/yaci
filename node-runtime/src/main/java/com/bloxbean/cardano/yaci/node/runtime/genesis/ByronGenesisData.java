package com.bloxbean.cardano.yaci.node.runtime.genesis;

import java.math.BigInteger;
import java.util.Map;

/**
 * Data extracted from a byron-genesis.json file.
 *
 * @param nonAvvmBalances Byron base58 address → lovelace
 * @param startTime       genesis start time (Unix epoch seconds)
 * @param protocolMagic   protocol magic number
 * @param slotDuration    slot duration in seconds (from blockVersionData.slotDuration ms / 1000)
 * @param k               security parameter from protocolConsts.k (epoch length = k * 10)
 */
public record ByronGenesisData(
        Map<String, BigInteger> nonAvvmBalances,
        long startTime,
        long protocolMagic,
        long slotDuration,
        long k
) {
    /**
     * Byron epoch length = k * 10 slots
     */
    public long epochLength() {
        return k * 10;
    }
}
