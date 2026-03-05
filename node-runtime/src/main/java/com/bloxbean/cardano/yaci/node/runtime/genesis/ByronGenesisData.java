package com.bloxbean.cardano.yaci.node.runtime.genesis;

import java.util.Map;

/**
 * Data extracted from a byron-genesis.json file.
 *
 * @param nonAvvmBalances Byron base58 address → lovelace
 * @param startTime       genesis start time (Unix epoch seconds)
 * @param protocolMagic   protocol magic number
 */
public record ByronGenesisData(
        Map<String, Long> nonAvvmBalances,
        long startTime,
        long protocolMagic
) {}
