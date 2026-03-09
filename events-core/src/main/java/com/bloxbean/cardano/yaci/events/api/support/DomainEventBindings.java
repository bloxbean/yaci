package com.bloxbean.cardano.yaci.events.api.support;

import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.api.SubscriptionHandle;
import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;

import java.util.List;

/**
 * SPI for build-time generated bindings of @DomainEventListener methods.
 * Implementations are discovered via ServiceLoader at runtime.
 */
public interface DomainEventBindings {
    /**
     * @return The target class this bindings instance supports.
     */
    Class<?> targetType();

    /**
     * Register all annotated methods for the given instance on the provided EventBus.
     * @param bus EventBus to register with
     * @param instance Instance containing listener methods
     * @param defaults Default subscription options to merge with annotation attributes
     * @return List of created subscription handles
     */
    List<SubscriptionHandle> register(EventBus bus, Object instance, SubscriptionOptions defaults);
}

