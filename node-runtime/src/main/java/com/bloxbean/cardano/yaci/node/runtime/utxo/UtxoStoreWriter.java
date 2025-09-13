package com.bloxbean.cardano.yaci.node.runtime.utxo;

import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.node.runtime.events.RollbackEvent;

public interface UtxoStoreWriter {
    void apply(MultiEraBlockTxs blockTxs);
    void rollbackTo(RollbackEvent e);
    void reconcile(ChainState chainState);
    boolean isEnabled();
}
