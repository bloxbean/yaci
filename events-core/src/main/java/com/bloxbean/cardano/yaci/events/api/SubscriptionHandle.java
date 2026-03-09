package com.bloxbean.cardano.yaci.events.api;

public interface SubscriptionHandle extends AutoCloseable {
    @Override
    void close();
    boolean isActive();
}

