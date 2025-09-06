package com.bloxbean.cardano.yaci.events.api;

@FunctionalInterface
public interface EventFilter<E extends Event> {
    boolean test(E event, EventMetadata metadata);
}

