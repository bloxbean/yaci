package com.bloxbean.cardano.yaci.node.runtime.utxo;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodically runs UTXO prune outside the apply hot path.
 * Uses a virtual-thread scheduled executor.
 */
public final class PruneService implements AutoCloseable {
    private final ScheduledExecutorService scheduler;
    private final Prunable prunable;
    private final long intervalMillis;

    public PruneService(Prunable prunable, long intervalMillis) {
        this.prunable = prunable;
        this.intervalMillis = Math.max(1000L, intervalMillis);
        this.scheduler = java.util.concurrent.Executors.newScheduledThreadPool(1, java.lang.Thread.ofVirtual().factory());
    }

    public void start() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                prunable.pruneOnce();
            } catch (Throwable ignored) { }
        }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
