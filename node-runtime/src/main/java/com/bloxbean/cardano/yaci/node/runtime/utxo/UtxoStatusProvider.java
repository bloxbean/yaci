package com.bloxbean.cardano.yaci.node.runtime.utxo;

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
    java.util.Map<String, Object> getMetrics();
    java.util.Map<String, Long> getCfEstimates();
}
