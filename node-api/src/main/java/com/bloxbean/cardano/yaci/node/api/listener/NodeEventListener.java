package com.bloxbean.cardano.yaci.node.api.listener;

import com.bloxbean.cardano.yaci.node.api.model.NodeStatus;

/**
 * Listener interface for node-level events.
 * Provides callbacks for node lifecycle and status changes.
 */
public interface NodeEventListener {
    
    /**
     * Called when the node starts up successfully.
     */
    default void onNodeStarted() {}
    
    /**
     * Called when the node is stopping or has stopped.
     */
    default void onNodeStopped() {}
    
    /**
     * Called when the node status changes significantly.
     * 
     * @param oldStatus the previous status
     * @param newStatus the new status
     */
    default void onStatusChanged(NodeStatus oldStatus, NodeStatus newStatus) {}
    
    /**
     * Called when sync starts.
     */
    default void onSyncStarted() {}
    
    /**
     * Called when sync completes (reaches remote tip).
     */
    default void onSyncCompleted() {}
    
    /**
     * Called when sync is interrupted or fails.
     * 
     * @param cause the cause of sync failure, if available
     */
    default void onSyncFailed(Throwable cause) {}
    
    /**
     * Called when the server component starts.
     */
    default void onServerStarted() {}
    
    /**
     * Called when the server component stops.
     */
    default void onServerStopped() {}
}