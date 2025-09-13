package com.bloxbean.cardano.yaci.node.runtime.utxo;

import com.bloxbean.cardano.yaci.core.model.Era;

import java.util.List;

/**
 * Era-agnostic, minimal transaction view for UTXO application.
 */
final class MultiEraBlockTxs {
    final Era era;
    final long slot;
    final long blockNumber;
    final String blockHash;
    final List<MultiEraTx> txs;

    MultiEraBlockTxs(Era era, long slot, long blockNumber, String blockHash, List<MultiEraTx> txs) {
        this.era = era;
        this.slot = slot;
        this.blockNumber = blockNumber;
        this.blockHash = blockHash;
        this.txs = txs;
    }
}

