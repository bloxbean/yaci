package com.bloxbean.cardano.yaci.helper;

/**
 * Different pipelining strategies for ChainSync and BlockFetch coordination
 */
public enum PipelineStrategy {
    
    /**
     * Sequential processing - each header triggers one body fetch
     * Compatible with existing behavior
     * Low memory usage, but slower performance
     */
    SEQUENTIAL,
    
    /**
     * Headers and bodies are processed independently with batching
     * Headers flow continuously, bodies are fetched in batches
     * Good balance of performance and resource usage
     */
    BATCH_PIPELINED,
    
    /**
     * Full parallelization with maximum pipelining
     * Multiple header requests in flight, multiple body batch requests in parallel
     * Highest performance but uses more resources
     */
    FULL_PARALLEL,
    
    /**
     * Headers-only mode - fetch headers but skip body fetching
     * Useful for fast header sync or header-only applications
     * Minimal resource usage
     */
    HEADERS_ONLY,
    
    /**
     * Selective body fetching based on header analysis
     * Headers flow continuously, bodies fetched only for selected headers
     * Good for partial sync scenarios
     */
    SELECTIVE_BODIES,
    
    /**
     * Adaptive strategy that switches between strategies based on conditions
     * Starts with full parallel, falls back to batch if resources are constrained
     * Smart resource management
     */
    ADAPTIVE
}