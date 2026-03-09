package com.bloxbean.cardano.yaci.events.api;

import java.lang.annotation.*;

/**
 * Marks a method as an event listener for automatic registration.
 * 
 * This annotation provides a declarative way to register event listeners
 * without boilerplate subscription code. Methods annotated with @DomainEventListener
 * will be automatically discovered and registered by AnnotationListenerRegistrar.
 * 
 * Method signatures:
 * The annotated method must have exactly one parameter:
 * - Direct event: void onEvent(MyEvent event)
 * - With context: {@code void onEvent(EventContext<MyEvent> ctx)}
 * 
 * The event type is inferred from the method parameter's generic type.
 * 
 * Example usage:
 * <pre>
 * public class MyPlugin implements NodePlugin {
 *     {@literal @}DomainEventListener(order = 100)
 *     public void onBlockApplied(BlockAppliedEvent event) {
 *         // Process the block
 *     }
 *     
 *     {@literal @}DomainEventListener(async = true)
 *     public void onRollback(EventContext&lt;RollbackEvent&gt; ctx) {
 *         // Handle rollback with access to metadata
 *         EventMetadata meta = ctx.metadata();
 *     }
 * }
 * </pre>
 * 
 * Registration:
 * Call AnnotationListenerRegistrar.register(eventBus, listenerObject, options)
 * to scan and register all annotated methods in the object.
 * 
 * @see com.bloxbean.cardano.yaci.events.api.support.AnnotationListenerRegistrar registration helper
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface DomainEventListener {
    /**
     * Execution order for listeners of the same event type.
     * Lower values execute first. Default is 0.
     * Useful for establishing processing pipelines.
     * 
     * @return Execution priority (lower = earlier)
     */
    int order() default 0;
    
    /**
     * Whether to execute this listener asynchronously.
     * When true, the listener runs off the publisher thread.
     * Executor selection:
     * - If a {@link com.bloxbean.cardano.yaci.events.api.SubscriptionOptions} default executor is provided
     *   during registration, it is used.
     * - Otherwise, a shared virtual-thread executor is used by default.
     * When false, the listener runs on the publisher thread.
     *
     * @return true for async execution, false for sync
     */
    boolean async() default false;
}
