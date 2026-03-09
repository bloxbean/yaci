package com.bloxbean.cardano.yaci.node.runtime.utxo;

import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import com.bloxbean.cardano.yaci.events.api.support.AnnotationListenerRegistrar;
import com.bloxbean.cardano.yaci.events.api.DomainEventListener;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.node.runtime.events.BlockAppliedEvent;
import com.bloxbean.cardano.yaci.node.runtime.events.RollbackEvent;

/**
 * Synchronous event handler that delegates to a UtxoStoreWriter.
 * Keeps ordering and atomicity identical to current behavior.
 */
public final class UtxoEventHandler implements AutoCloseable {
    private final UtxoStoreWriter writer;
    private final java.util.List<com.bloxbean.cardano.yaci.events.api.SubscriptionHandle> handles;

    public UtxoEventHandler(EventBus bus, UtxoStoreWriter writer) {
        this.writer = writer;
        SubscriptionOptions defaults = SubscriptionOptions.builder().build();
        this.handles = AnnotationListenerRegistrar.register(bus, this, defaults);
    }

    @DomainEventListener(order = 100)
    public void onBlockApplied(BlockAppliedEvent e) {
        if (writer != null && writer.isEnabled()) writer.applyBlock(e);
    }

    @DomainEventListener(order = 100)
    public void onRollback(RollbackEvent e) {
        if (writer != null && writer.isEnabled()) writer.rollbackTo(e);
    }

    @Override
    public void close() {
        if (handles != null) handles.forEach(h -> { try { h.close(); } catch (Exception ignored) {} });
    }
}

