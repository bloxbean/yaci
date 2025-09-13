package com.bloxbean.cardano.yaci.node.runtime.utxo;

import com.bloxbean.cardano.yaci.events.api.DomainEventListener;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import com.bloxbean.cardano.yaci.events.api.support.AnnotationListenerRegistrar;
import com.bloxbean.cardano.yaci.node.runtime.events.BlockAppliedEvent;
import com.bloxbean.cardano.yaci.node.runtime.events.ByronMainBlockAppliedEvent;
import com.bloxbean.cardano.yaci.node.runtime.events.RollbackEvent;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Optional async UTXO event handler that preserves ordering using a single-thread executor.
 * Apply/rollback are offloaded off the publisher thread but executed sequentially.
 */
public final class UtxoEventHandlerAsync implements AutoCloseable {
    private final UtxoStoreWriter writer;
    private final ExecutorService single;
    private final List<com.bloxbean.cardano.yaci.events.api.SubscriptionHandle> handles;

    public UtxoEventHandlerAsync(EventBus bus, UtxoStoreWriter writer) {
        this.writer = writer;
        this.single = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "utxo-apply-1");
            t.setDaemon(true);
            return t;
        });
        SubscriptionOptions defaults = SubscriptionOptions.builder().build();
        this.handles = AnnotationListenerRegistrar.register(bus, this, defaults);
    }

    @DomainEventListener(order = 100)
    public void onBlockApplied(BlockAppliedEvent e) {
        if (writer == null || !writer.isEnabled()) return;
        if (e.block() == null) return; // Byron handled separately
        single.execute(() -> writer.apply(UtxoTxNormalizer.fromShelley(e.era(), e.slot(), e.blockNumber(), e.blockHash(), e.block())));
    }

    @DomainEventListener(order = 100)
    public void onRollback(RollbackEvent e) {
        if (writer == null || !writer.isEnabled()) return;
        single.execute(() -> writer.rollbackTo(e));
    }

    @DomainEventListener(order = 100)
    public void onByronMainApplied(ByronMainBlockAppliedEvent e) {
        if (writer == null || !writer.isEnabled()) return;
        single.execute(() -> writer.apply(UtxoTxNormalizer.fromByron(e.slot(), e.blockNumber(), e.blockHash(), e.block())));
    }

    @Override
    public void close() {
        try { if (handles != null) handles.forEach(h -> { try { h.close(); } catch (Exception ignored) {} }); } catch (Exception ignored) {}
        single.shutdownNow();
    }
}
