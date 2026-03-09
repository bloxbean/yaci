package com.bloxbean.cardano.yaci.node.runtime.utxo;

import java.util.Map;

public interface UtxoStatusProvider {
    boolean isEnabled();
    String storeType();
    long getLastAppliedBlock();
    long getLastAppliedSlot();
    int getPruneDepth();
    int getRollbackWindow();
    int getPruneBatchSize();
    byte[] getDeltaCursorKey();
    byte[] getSpentCursorKey();
    Map<String, Object> getMetrics();
    Map<String, Long> getCfEstimates();
}
