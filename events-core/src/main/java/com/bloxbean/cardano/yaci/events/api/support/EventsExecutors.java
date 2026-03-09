package com.bloxbean.cardano.yaci.events.api.support;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shared executors for the events system.
 * Provides a default virtual-thread executor for async listener offload.
 */
public final class EventsExecutors {
    private EventsExecutors() {}

    private static final ExecutorService VIRTUAL = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Default executor backed by virtual threads (Java 21).
     * Intended for offloading @DomainEventListener(async=true) when no executor is supplied.
     */
    public static ExecutorService virtual() {
        return VIRTUAL;
    }
}

