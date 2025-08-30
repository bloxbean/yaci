package com.bloxbean.cardano.yaci.node.api;

import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import com.bloxbean.cardano.yaci.node.api.config.NodeConfig;
import com.bloxbean.cardano.yaci.node.api.listener.NodeEventListener;
import com.bloxbean.cardano.yaci.node.api.model.NodeStatus;

/**
 * Main interface for Yaci Node operations.
 * Provides a framework-agnostic API for node lifecycle management, 
 * status monitoring, and blockchain data access.
 */
public interface NodeAPI {
    
    /**
     * Start the node with its configured settings.
     * This will initialize client and/or server components based on configuration.
     * 
     * @throws IllegalStateException if the node is already running
     * @throws RuntimeException if startup fails
     */
    void start();
    
    /**
     * Stop the node and cleanup all resources.
     * This will gracefully shutdown client and server components.
     */
    void stop();
    
    /**
     * Check if the node is currently running.
     * 
     * @return true if the node is running, false otherwise
     */
    boolean isRunning();
    
    /**
     * Check if the node is currently syncing with remote peers.
     * 
     * @return true if actively syncing, false otherwise
     */
    boolean isSyncing();
    
    /**
     * Check if the server component is running and accepting connections.
     * 
     * @return true if server is running, false otherwise
     */
    boolean isServerRunning();
    
    /**
     * Get the current status of the node including sync progress and statistics.
     * 
     * @return current node status
     */
    NodeStatus getStatus();
    
    /**
     * Get the current tip of the local chain.
     * 
     * @return the local chain tip, or null if no blocks have been processed
     */
    ChainTip getLocalTip();
    
    /**
     * Add a listener for blockchain data events.
     * The listener will receive callbacks for blocks, rollbacks, and other chain events.
     * 
     * @param listener the blockchain data listener to add
     */
    void addBlockChainDataListener(BlockChainDataListener listener);
    
    /**
     * Remove a previously added blockchain data listener.
     * 
     * @param listener the blockchain data listener to remove
     */
    void removeBlockChainDataListener(BlockChainDataListener listener);
    
    /**
     * Add a listener for node-level events (startup, shutdown, status changes).
     * 
     * @param listener the node event listener to add
     */
    void addNodeEventListener(NodeEventListener listener);
    
    /**
     * Remove a previously added node event listener.
     * 
     * @param listener the node event listener to remove
     */
    void removeNodeEventListener(NodeEventListener listener);
    
    /**
     * Get access to the underlying ChainState for advanced operations.
     * This provides direct access to block storage and chain queries.
     * 
     * @return the chain state instance
     */
    ChainState getChainState();
    
    /**
     * Get the configuration used by this node.
     * 
     * @return the node configuration
     */
    NodeConfig getConfig();
    
    /**
     * Recover chain state from corruption by finding the last valid continuous point
     * and removing all corrupted data after that point.
     * 
     * This method should only be called when the node is not running.
     * 
     * @return true if recovery was performed, false if no corruption was detected
     * @throws IllegalStateException if the node is currently running
     * @throws RuntimeException if recovery fails
     */
    boolean recoverChainState();
}