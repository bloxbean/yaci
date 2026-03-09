package com.bloxbean.cardano.yaci.events.api;

@FunctionalInterface
public interface EventListener<E extends Event> {
    void onEvent(EventContext<E> ctx) throws Exception;
}

