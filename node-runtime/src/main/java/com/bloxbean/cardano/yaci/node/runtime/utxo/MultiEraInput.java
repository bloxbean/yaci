package com.bloxbean.cardano.yaci.node.runtime.utxo;

final class MultiEraInput {
    final String txHash;
    final int index;

    MultiEraInput(String txHash, int index) {
        this.txHash = txHash;
        this.index = index;
    }
}

