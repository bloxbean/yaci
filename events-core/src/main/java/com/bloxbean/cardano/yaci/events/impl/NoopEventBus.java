package com.bloxbean.cardano.yaci.events.impl;

import com.bloxbean.cardano.yaci.events.api.*;

public final class NoopEventBus implements EventBus {
    private static final SubscriptionHandle INACTIVE = new SubscriptionHandle() {
        @Override public void close() {}
        @Override public boolean isActive() { return false; }
    };

    @Override
    public <E extends Event> SubscriptionHandle subscribe(Class<E> type, EventListener<E> listener, SubscriptionOptions options) {
        return INACTIVE;
    }

    @Override
    public <E extends Event> void publish(E event, EventMetadata metadata, PublishOptions options) {
        // no-op
    }

    @Override
    public void close() {
        // no-op
    }
}

