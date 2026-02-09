package com.bloxbean.cardano.yaci.node.api.plugin;

import java.util.Set;

/**
 * Base interface for Yaci node plugins.
 * 
 * Plugins extend the functionality of the Yaci node by:
 * - Listening to blockchain events (blocks, transactions, rollbacks)
 * - Providing custom storage implementations
 * - Adding notification mechanisms
 * - Implementing policy decisions
 * 
 * Plugin lifecycle:
 * 1. Discovery - Via ServiceLoader or programmatic registration
 * 2. init() - Receive context and register listeners/services
 * 3. start() - Begin active processing
 * 4. stop() - Graceful shutdown of processing
 * 5. close() - Release all resources
 * 
 * Plugin discovery:
 * - Automatic: Place plugin JAR in classpath with META-INF/services/NodePlugin
 * - Programmatic: Register via NodeRuntimeBuilder.withPlugins()
 * 
 * Best practices:
 * - Use unique reverse-DNS naming for plugin IDs (e.g., "com.example.myplugin")
 * - Handle exceptions gracefully to avoid affecting node stability
 * - Clean up resources properly in stop() and close()
 * - Use provided ScheduledExecutorService for background tasks
 * - Respect dependency ordering via dependsOn()
 * 
 * @see PluginContext for available services
 * @see PluginCapability for declaring plugin features
 */
public interface NodePlugin extends AutoCloseable {
    /**
     * Unique identifier for this plugin.
     * Should use reverse-DNS naming convention (e.g., "com.example.analytics").
     * 
     * @return Plugin identifier
     */
    String id();
    
    /**
     * Version of this plugin.
     * Recommended to use semantic versioning (e.g., "1.0.0").
     * 
     * @return Plugin version string
     */
    String version();

    /**
     * Declare dependencies on other plugins.
     * The plugin manager will ensure dependencies are initialized first.
     * Circular dependencies will be detected and logged.
     * 
     * @return Set of plugin IDs this plugin depends on
     */
    default Set<String> dependsOn() { return Set.of(); }
    
    /**
     * Declare the capabilities this plugin provides.
     * Used for documentation and future capability-based filtering.
     * 
     * @return Set of capabilities this plugin provides
     */
    default Set<PluginCapability> capabilities() { return Set.of(PluginCapability.EVENT_CONSUMER); }

    /**
     * Initialize the plugin with runtime context.
     * Called once during plugin manager initialization.
     * Use this to:
     * - Register event listeners
     * - Access configuration
     * - Register services for other plugins
     * 
     * @param ctx Plugin context providing access to event bus, config, etc.
     */
    void init(PluginContext ctx);
    
    /**
     * Start active plugin processing.
     * Called after all plugins are initialized.
     * Use this to begin background tasks or active monitoring.
     */
    void start();
    
    /**
     * Stop active plugin processing.
     * Called during graceful shutdown.
     * Should stop background tasks and prepare for close().
     */
    void stop();

    /**
     * Release all plugin resources.
     * Called as final cleanup step.
     * Must be idempotent (safe to call multiple times).
     */
    @Override
    void close();
}

