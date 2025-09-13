package com.bloxbean.cardano.yaci.node.runtime.utxo;

import org.rocksdb.ColumnFamilyHandle;

/**
 * Prepares per-block apply context (e.g., batched input prefetch) to reduce JNI/IO.
 */
public interface UtxoProcessor {
    ApplyContext prepare(MultiEraBlockTxs blockTxs, ColumnFamilyHandle cfUnspent);

    interface ApplyContext extends AutoCloseable {
        byte[] getUnspent(byte[] outpointKey);
        @Override default void close() {}
    }
}
