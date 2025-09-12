package com.bloxbean.cardano.yaci.node.runtime.utxo;

import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.node.runtime.events.BlockAppliedEvent;
import com.bloxbean.cardano.yaci.node.runtime.events.RollbackEvent;

public interface UtxoStoreWriter {
    void applyBlock(BlockAppliedEvent e);
    void rollbackTo(RollbackEvent e);
    void reconcile(ChainState chainState);
    boolean isEnabled();
}
