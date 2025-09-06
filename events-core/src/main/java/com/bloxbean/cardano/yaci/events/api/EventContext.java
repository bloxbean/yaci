package com.bloxbean.cardano.yaci.events.api;

public interface EventContext<E extends Event> {
    E event();
    EventMetadata metadata();

    default void ack() {}
    default void nack(Throwable t) {}
}

