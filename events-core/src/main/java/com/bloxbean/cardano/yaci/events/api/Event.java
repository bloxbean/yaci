package com.bloxbean.cardano.yaci.events.api;

/**
 * Marker interface for all Yaci events.
 * 
 * This is the base interface that all events in the Yaci event system must implement.
 * It serves as a type marker to ensure type safety in the event bus publish/subscribe
 * mechanism. Events are lightweight data carriers that represent state changes or
 * notifications within the Yaci node runtime.
 * 
 * Implementation notes:
 * - Events should be immutable value objects (use final fields)
 * - Events should be serializable if distributed event buses are used
 * - Events should contain only the necessary data for processing
 * - Consider using record classes for simple event implementations
 * 
 * @see EventBus
 * @see EventListener
 * @see EventContext
 */
public interface Event {}

