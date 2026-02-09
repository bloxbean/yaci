package com.bloxbean.cardano.yaci.node.runtime.events;

import com.bloxbean.cardano.yaci.events.api.Event;
import com.bloxbean.cardano.yaci.node.runtime.chain.MemPoolTransaction;

/**
 * Event published when a transaction is received and added to the mempool.
 */
public final class MemPoolTransactionReceivedEvent implements Event {
    private final MemPoolTransaction transaction;

    public MemPoolTransactionReceivedEvent(MemPoolTransaction transaction) {
        this.transaction = transaction;
    }

    public MemPoolTransaction transaction() {
        return transaction;
    }
}

