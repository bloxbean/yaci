package com.bloxbean.cardano.yaci.node.api.config;

/**
 * Base interface for node configuration.
 * Implementations provide specific configuration options for different node types.
 */
public interface NodeConfig {
    
    /**
     * Validate the configuration.
     * 
     * @throws IllegalArgumentException if the configuration is invalid
     */
    void validate();
    
    /**
     * Check if client mode is enabled (syncing with remote nodes)
     */
    boolean isClientEnabled();
    
    /**
     * Check if server mode is enabled (serving other clients)
     */
    boolean isServerEnabled();
    
    /**
     * Get the protocol magic number for the target network
     */
    long getProtocolMagic();
}