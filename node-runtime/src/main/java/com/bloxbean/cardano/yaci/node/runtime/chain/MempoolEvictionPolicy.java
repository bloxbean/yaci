package com.bloxbean.cardano.yaci.node.runtime.chain;

import com.bloxbean.cardano.yaci.node.api.events.BlockAppliedEvent;

/**
 * Policy for evicting transactions from the mempool.
 * Implementations define when and which transactions to remove.
 */
public interface MempoolEvictionPolicy {
    /** Called when a block is applied — evict confirmed transactions. */
    void onBlockApplied(BlockAppliedEvent event);

    /** Called periodically — evict expired or excess transactions. */
    void onPeriodicCheck();
}
