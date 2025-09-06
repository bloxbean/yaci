package com.bloxbean.cardano.yaci.events.api;

/**
 * Central event bus interface for publishing and subscribing to events.
 * 
 * The EventBus provides a decoupled communication mechanism between components
 * in the Yaci node runtime. It supports both synchronous and asynchronous event
 * delivery based on subscription options. Multiple implementations can be provided
 * for different performance characteristics and deployment scenarios.
 * 
 * Key features:
 * - Type-safe publish/subscribe with generics
 * - Configurable delivery semantics (sync/async)
 * - Backpressure handling with overflow strategies
 * - Event filtering and metadata support
 * - Graceful shutdown with resource cleanup
 * 
 * Thread safety: Implementations must be thread-safe for concurrent access.
 * 
 * Delivery guarantees: At-least-once delivery within a single JVM process.
 * Events may be delivered multiple times in case of retries.
 * 
 * @see com.bloxbean.cardano.yaci.events.impl.SimpleEventBus default in-process implementation
 * @see com.bloxbean.cardano.yaci.events.impl.NoopEventBus testing/disabled-events implementation
 */
public interface EventBus extends AutoCloseable {
    /**
     * Subscribe to events of a specific type.
     * 
     * @param <E> The event type
     * @param type The class of events to subscribe to
     * @param listener The listener that will handle events
     * @param options Configuration for the subscription (buffering, async, etc.)
     * @return A handle to manage the subscription lifecycle
     */
    <E extends Event> SubscriptionHandle subscribe(Class<E> type, EventListener<E> listener, SubscriptionOptions options);
    
    /**
     * Publish an event to all registered listeners.
     * 
     * @param <E> The event type
     * @param event The event to publish
     * @param metadata Metadata about the event (timestamp, origin, chain position, etc.)
     * @param options Publishing options (async hint, priority, etc.)
     */
    <E extends Event> void publish(E event, EventMetadata metadata, PublishOptions options);
    
    /**
     * Close the event bus and release all resources.
     * 
     * This method will:
     * - Stop accepting new events
     * - Drain pending async events (with timeout)
     * - Cancel all subscriptions
     * - Release thread pools and other resources
     */
    @Override
    void close();
}
