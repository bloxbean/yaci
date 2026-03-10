package com.bloxbean.cardano.yaci.node.runtime.bootstrap;

/**
 * Result of a bootstrap operation.
 *
 * @param blockNumber     the bootstrap tip block number
 * @param blockHash       the bootstrap tip block hash (hex)
 * @param slot            the bootstrap tip slot
 * @param blocksCreated   number of synthetic blocks stored
 * @param utxosInjected   total UTXOs injected
 */
public record BootstrapResult(
        long blockNumber,
        String blockHash,
        long slot,
        int blocksCreated,
        int utxosInjected
) {}
