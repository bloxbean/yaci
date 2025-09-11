package com.bloxbean.cardano.yaci.node.runtime;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight, thread-safe cache for the latest network tip slot observed via ChainSync.
 * Updated by HeaderSyncManager (on ChainSync callbacks) and read by BodyFetchManager
 * to make near-tip decisions without per-block network calls.
 */
public final class SyncTipContext {
    private final AtomicLong networkTipSlot = new AtomicLong(-1L);
    private final AtomicLong lastUpdateEpochMs = new AtomicLong(0L);

    public void update(Tip tip) {
        if (tip != null && tip.getPoint() != null) {
            networkTipSlot.set(Math.max(0L, tip.getPoint().getSlot()));
            lastUpdateEpochMs.set(System.currentTimeMillis());
        }
    }

    public void invalidate() {
        networkTipSlot.set(-1L);
        lastUpdateEpochMs.set(System.currentTimeMillis());
    }

    public long getNetworkTipSlot() {
        return networkTipSlot.get();
    }

    public long getLastUpdateEpochMs() {
        return lastUpdateEpochMs.get();
    }
}

