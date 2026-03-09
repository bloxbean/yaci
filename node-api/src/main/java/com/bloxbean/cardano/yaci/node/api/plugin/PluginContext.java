package com.bloxbean.cardano.yaci.node.api.plugin;

import com.bloxbean.cardano.yaci.events.api.EventBus;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Runtime context provided to plugins during initialization.
 * 
 * PluginContext gives plugins access to core node services and facilities
 * without exposing internal implementation details. This abstraction layer
 * ensures plugins remain decoupled from the node runtime internals.
 * 
 * Available services:
 * - Event bus for publish/subscribe communication
 * - Logger for plugin-specific logging
 * - Configuration map for plugin settings
 * - Scheduler for background tasks
 * - Service registry for inter-plugin communication
 * 
 * Thread safety: All methods are thread-safe and can be called concurrently.
 * 
 * @see NodePlugin#init(PluginContext)
 */
public interface PluginContext {
    /**
     * Get the event bus for publishing and subscribing to events.
     * 
     * Plugins should use this to:
     * - Listen for blockchain events (blocks, transactions, rollbacks)
     * - Publish custom events for other plugins
     * - Implement reactive processing chains
     * 
     * @return The configured event bus instance
     */
    EventBus eventBus();
    
    /**
     * Get a logger configured for this plugin.
     * 
     * The logger will be named after the plugin ID for easy filtering
     * and debugging in production environments.
     * 
     * @return Logger instance for this plugin
     */
    Logger logger();
    
    /**
     * Get plugin-specific configuration.
     * 
     * Configuration can come from:
     * - System properties (yaci.plugins.{pluginId}.*)
     * - Configuration files
     * - Programmatic configuration via builder
     * 
     * @return Immutable configuration map
     */
    Map<String, Object> config();
    
    /**
     * Get a shared scheduler for background tasks.
     * 
     * Plugins should use this scheduler instead of creating their own
     * thread pools to avoid resource exhaustion. The scheduler is
     * configured with appropriate pool size for the deployment.
     * 
     * @return Shared scheduled executor service
     */
    ScheduledExecutorService scheduler();
    
    /**
     * Get the classloader used to load this plugin.
     * 
     * Useful for plugins that need to load resources or classes
     * dynamically. May be empty if default classloader is used.
     * 
     * @return Optional plugin classloader
     */
    Optional<ClassLoader> pluginClassLoader();

    /**
     * Register a service for other plugins to use.
     * 
     * Services enable inter-plugin communication without tight coupling.
     * Common use cases:
     * - Storage adapters registering data access services
     * - Notification plugins providing alert mechanisms
     * - Analytics plugins exposing metrics
     * 
     * @param key Unique service identifier
     * @param service The service instance to register
     */
    void registerService(String key, Object service);
    
    /**
     * Get a service registered by another plugin.
     * 
     * Services are looked up by key and cast to the requested type.
     * Returns empty if service not found or type mismatch.
     * 
     * @param <T> Expected service type
     * @param key Service identifier
     * @param type Expected service class
     * @return Optional service instance
     */
    <T> Optional<T> getService(String key, Class<T> type);
}

