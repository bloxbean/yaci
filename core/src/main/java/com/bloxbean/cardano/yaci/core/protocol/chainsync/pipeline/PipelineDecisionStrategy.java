package com.bloxbean.cardano.yaci.core.protocol.chainsync.pipeline;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;

/**
 * Strategy interface for making pipeline decisions in ChainSync protocol.
 * 
 * This interface is inspired by the Haskell ouroboros-network implementation's
 * MkPipelineDecision pattern, allowing for pluggable pipeline strategies
 * that can adapt to different network conditions and requirements.
 * 
 * The strategy receives information about the current pipeline state
 * (number of outstanding requests), client/server synchronization status,
 * and network conditions to make informed decisions about whether to:
 * - Send non-pipelined requests
 * - Pipeline additional requests  
 * - Collect responses
 * - Adapt behavior dynamically
 */
public interface PipelineDecisionStrategy {
    
    /**
     * Make a pipeline decision based on current conditions.
     * 
     * @param outstandingRequests number of pipelined requests currently in flight
     * @param clientTipSlot the slot number of the client's current tip
     * @param serverTipSlot the slot number of the server's current tip  
     * @param networkMetrics current network and system metrics
     * @return the pipeline decision for the next action
     */
    PipelineDecision decide(int outstandingRequests, 
                           long clientTipSlot, 
                           long serverTipSlot, 
                           NetworkMetrics networkMetrics);
    
    /**
     * Get the name of this strategy for logging and debugging purposes.
     * 
     * @return human-readable strategy name
     */
    String getStrategyName();
    
    /**
     * Get the maximum number of requests this strategy will pipeline.
     * This is used for capacity planning and overflow prevention.
     * 
     * @return maximum pipeline depth, or -1 for no limit
     */
    int getMaxPipelineDepth();
    
    /**
     * Called when a pipelined request completes successfully.
     * Strategies can use this to track performance and adjust behavior.
     * 
     * @param responseTimeMs time taken for the request to complete
     */
    default void onRequestCompleted(long responseTimeMs) {
        // Default implementation does nothing
    }
    
    /**
     * Called when a pipelined request fails.
     * Strategies can use this to reduce aggressiveness or switch modes.
     * 
     * @param cause the failure cause
     */
    default void onRequestFailed(Throwable cause) {
        // Default implementation does nothing
    }
}