package com.bloxbean.cardano.yaci.node.runtime.chain;

import com.bloxbean.cardano.yaci.node.api.model.MemPoolTransaction;

import java.util.Set;

public interface MemPool {
    // Add a transaction to the mempool and return the created mempool transaction
    MemPoolTransaction addTransaction(byte[] txBytes);

    // Get the next transaction to process (FIFO)
    MemPoolTransaction getNextTransaction();

    // Check if the mempool is empty
    boolean isEmpty();

    // Get the current size of the mempool
    int size();

    // Clear the mempool
    void clear();

    /** Remove transactions confirmed in a block. Returns count removed. */
    int removeByTxHashes(Set<String> txHashes);

    /** Evict the oldest N transactions. Returns actual count evicted. */
    int evictOldest(int count);

    /** Remove transactions inserted before the given timestamp. Returns count removed. */
    int removeOlderThan(long beforeEpochMillis);
}
