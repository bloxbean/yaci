package com.bloxbean.cardano.yaci.node.api.utxo.model;

/**
 * Transaction outpoint (tx hash + output index).
 */
public record Outpoint(String txHash, int index) {}

