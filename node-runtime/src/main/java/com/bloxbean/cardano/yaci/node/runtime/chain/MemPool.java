package com.bloxbean.cardano.yaci.node.runtime.chain;

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


}
