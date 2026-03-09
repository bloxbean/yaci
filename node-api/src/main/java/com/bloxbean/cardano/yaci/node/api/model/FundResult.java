package com.bloxbean.cardano.yaci.node.api.model;

/**
 * Result of a faucet fund operation — the synthetic UTXO reference.
 */
public record FundResult(
    String txHash,
    int index,
    long lovelace
) {}
