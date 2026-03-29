package com.bloxbean.cardano.yaci.node.ledgerstate;

import com.bloxbean.cardano.yaci.events.api.DomainEventListener;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.api.SubscriptionHandle;
import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import com.bloxbean.cardano.yaci.events.api.support.AnnotationListenerRegistrar;
import com.bloxbean.cardano.yaci.node.api.account.AccountStateStore;
import com.bloxbean.cardano.yaci.node.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yaci.node.api.events.EpochTransitionEvent;
import com.bloxbean.cardano.yaci.node.api.events.PostEpochTransitionEvent;
import com.bloxbean.cardano.yaci.node.api.events.PreEpochTransitionEvent;
import com.bloxbean.cardano.yaci.node.api.events.RollbackEvent;

import java.util.List;

/**
 * Synchronous event handler for account state.
 * Same pattern as UtxoEventHandler.
 */
public final class AccountStateEventHandler implements AutoCloseable {
    private final AccountStateStore store;
    private final List<SubscriptionHandle> handles;

    public AccountStateEventHandler(EventBus bus, AccountStateStore store) {
        this.store = store;
        SubscriptionOptions defaults = SubscriptionOptions.builder().build();
        this.handles = AnnotationListenerRegistrar.register(bus, this, defaults);
    }

    @DomainEventListener(order = 110)
    public void onPreEpochTransition(PreEpochTransitionEvent e) {
        if (store != null && store.isEnabled()) store.handleEpochTransition(e.previousEpoch(), e.newEpoch());
    }

    @DomainEventListener(order = 110)
    public void onEpochTransition(EpochTransitionEvent e) {
        if (store != null && store.isEnabled()) store.handleEpochTransitionSnapshot(e.previousEpoch(), e.newEpoch());
    }

    @DomainEventListener(order = 110)
    public void onPostEpochTransition(PostEpochTransitionEvent e) {
        if (store != null && store.isEnabled()) store.handlePostEpochTransition(e.previousEpoch(), e.newEpoch());
    }

    @DomainEventListener(order = 110)
    public void onBlockApplied(BlockAppliedEvent e) {
        if (store != null && store.isEnabled()) store.applyBlock(e);
    }

    @DomainEventListener(order = 110)
    public void onRollback(RollbackEvent e) {
        if (store != null && store.isEnabled()) store.rollbackTo(e);
    }

    @Override
    public void close() {
        if (handles != null) handles.forEach(h -> { try { h.close(); } catch (Exception ignored) {} });
    }
}
