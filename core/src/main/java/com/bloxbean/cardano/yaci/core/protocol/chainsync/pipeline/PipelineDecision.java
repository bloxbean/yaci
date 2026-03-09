package com.bloxbean.cardano.yaci.core.protocol.chainsync.pipeline;

/**
 * Pipeline decision types that determine the next action for ChainSync pipelining.
 * These mirror the decision types used in the Haskell ouroboros-network implementation
 * to provide consistent and well-tested pipeline behavior.
 */
public enum PipelineDecision {
    /**
     * Send a non-pipelined request. Used when we're at the server's tip
     * or when pipelining is disabled.
     */
    REQUEST,
    
    /**
     * Pipeline the next request. Used when we're behind the server's tip
     * and have capacity for more pipelined requests.
     */
    PIPELINE,
    
    /**
     * Collect a response or pipeline another request based on current conditions.
     * This provides adaptive behavior where the strategy can decide dynamically
     * whether to collect or pipeline based on system state.
     */
    COLLECT_OR_PIPELINE,
    
    /**
     * Must collect a response. Used when we've hit pipeline limits,
     * are synchronized with the server's tip, or need to reduce
     * outstanding requests.
     */
    COLLECT
}