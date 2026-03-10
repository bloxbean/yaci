package com.bloxbean.cardano.yaci.node.api.bootstrap;

/**
 * Block metadata fetched from a bootstrap data provider.
 *
 * @param blockHash         block header hash (hex)
 * @param blockNumber       block height
 * @param slot              absolute slot number
 * @param previousBlockHash previous block header hash (hex), null for genesis
 */
public record BootstrapBlockInfo(
        String blockHash,
        long blockNumber,
        long slot,
        String previousBlockHash
) {}
