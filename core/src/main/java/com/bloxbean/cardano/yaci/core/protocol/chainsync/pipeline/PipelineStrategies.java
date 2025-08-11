package com.bloxbean.cardano.yaci.core.protocol.chainsync.pipeline;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.pipeline.strategies.*;

/**
 * Factory class providing pre-configured pipeline strategies for common use cases.
 * 
 * This class provides convenient access to well-tested strategy configurations
 * that match different application requirements and network conditions.
 */
public final class PipelineStrategies {
    
    private PipelineStrategies() {
        // Utility class
    }
    
    /**
     * Sequential strategy that disables pipelining entirely.
     * Best for legacy compatibility and debugging.
     * 
     * @return sequential strategy instance
     */
    public static PipelineDecisionStrategy sequential() {
        return new SequentialStrategy();
    }
    
    /**
     * Conservative pipelining strategy with eager collection.
     * Good for unstable networks or low-latency connections.
     * 
     * @param maxDepth maximum number of pipelined requests
     * @return min pipeline strategy instance
     */
    public static PipelineDecisionStrategy conservative(int maxDepth) {
        return new MinPipelineStrategy(maxDepth);
    }
    
    /**
     * Conservative pipelining with default depth (25).
     * 
     * @return min pipeline strategy instance
     */
    public static PipelineDecisionStrategy conservative() {
        return new MinPipelineStrategy();
    }
    
    /**
     * Aggressive pipelining strategy for maximum throughput.
     * Best for stable, high-latency networks.
     * 
     * @param maxDepth maximum number of pipelined requests
     * @return max pipeline strategy instance
     */
    public static PipelineDecisionStrategy aggressive(int maxDepth) {
        return new MaxPipelineStrategy(maxDepth);
    }
    
    /**
     * Aggressive pipelining with default depth (50).
     * 
     * @return max pipeline strategy instance
     */
    public static PipelineDecisionStrategy aggressive() {
        return new MaxPipelineStrategy();
    }
    
    /**
     * Adaptive watermark-based strategy for general use.
     * Balances throughput and memory usage with automatic adaptation.
     * 
     * @param lowMark pipeline low watermark
     * @param highMark pipeline high watermark
     * @return watermark strategy instance
     */
    public static PipelineDecisionStrategy adaptive(int lowMark, int highMark) {
        return new WatermarkPipelineStrategy(lowMark, highMark);
    }
    
    /**
     * Adaptive strategy with default watermarks (10/50).
     * Recommended for most applications.
     * 
     * @return watermark strategy instance
     */
    public static PipelineDecisionStrategy adaptive() {
        return new WatermarkPipelineStrategy();
    }
    
    /**
     * Get the default strategy recommended for most use cases.
     * Currently returns adaptive watermark strategy.
     * 
     * @return default strategy instance
     */
    public static PipelineDecisionStrategy defaultStrategy() {
        return adaptive();
    }
    
    /**
     * Create a strategy optimized for sync-from-genesis scenarios.
     * Uses aggressive pipelining for maximum sync speed.
     * 
     * @return strategy optimized for initial sync
     */
    public static PipelineDecisionStrategy syncFromGenesis() {
        return new MaxPipelineStrategy(100); // Higher depth for initial sync
    }
    
    /**
     * Create a strategy optimized for near-tip following.
     * Uses conservative pipelining to minimize latency.
     * 
     * @return strategy optimized for tip following
     */
    public static PipelineDecisionStrategy followTip() {
        return new MinPipelineStrategy(5); // Low depth for quick response
    }
    
    /**
     * Create a strategy based on string name for configuration.
     * 
     * @param strategyName strategy name (sequential, conservative, aggressive, adaptive)
     * @param maxDepth maximum pipeline depth (ignored for strategies with fixed depth)
     * @return strategy instance
     * @throws IllegalArgumentException if strategy name is not recognized
     */
    public static PipelineDecisionStrategy fromName(String strategyName, int maxDepth) {
        switch (strategyName.toLowerCase()) {
            case "sequential":
                return sequential();
            case "conservative":
            case "min":
                return conservative(maxDepth);
            case "aggressive":  
            case "max":
                return aggressive(maxDepth);
            case "adaptive":
            case "watermark":
                // For watermark, use maxDepth as high mark, low mark = maxDepth/5
                int lowMark = Math.max(1, maxDepth / 5);
                return adaptive(lowMark, maxDepth);
            default:
                throw new IllegalArgumentException("Unknown strategy: " + strategyName + 
                    ". Valid options: sequential, conservative, aggressive, adaptive");
        }
    }
    
    /**
     * Create a strategy based on string name with default depth.
     * 
     * @param strategyName strategy name
     * @return strategy instance
     */
    public static PipelineDecisionStrategy fromName(String strategyName) {
        return fromName(strategyName, 50);
    }
}