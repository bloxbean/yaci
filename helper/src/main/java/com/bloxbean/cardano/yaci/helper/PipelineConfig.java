package com.bloxbean.cardano.yaci.helper;

import lombok.Builder;
import lombok.Data;

import java.time.Duration;

/**
 * Configuration for pipelining behavior in N2NPeerFetcher
 */
@Data
@Builder
public class PipelineConfig {
    
    /**
     * Maximum number of headers that can be in flight (requested but not yet received)
     * Higher values = more parallelism but more memory usage
     * Default: 100
     */
    @Builder.Default
    private int headerPipelineDepth = 100;
    
    /**
     * Number of block bodies to fetch in a single batch
     * Higher values = better network efficiency but more memory usage
     * Default: 20
     */
    @Builder.Default
    private int bodyBatchSize = 20;
    
    /**
     * Maximum number of concurrent body fetch requests
     * Higher values = more parallelism but more network connections
     * Default: 5
     */
    @Builder.Default
    private int maxParallelBodies = 5;
    
    /**
     * Timeout for batching bodies before sending incomplete batch
     * Prevents indefinite waiting for incomplete batches
     * Default: 1 second
     */
    @Builder.Default
    private Duration batchTimeout = Duration.ofSeconds(1);
    
    /**
     * Enable selective body fetching based on header analysis
     * When true, only fetch bodies for headers that pass the filter
     * Default: false (fetch all bodies)
     */
    @Builder.Default
    private boolean enableSelectiveBodyFetch = false;
    
    /**
     * Buffer size for pending headers waiting for body fetch
     * Should be larger than headerPipelineDepth to avoid blocking
     * Default: 500
     */
    @Builder.Default
    private int headerBufferSize = 500;
    
    /**
     * Enable parallel processing of headers and bodies
     * When true, header processing and body fetching run in separate threads
     * Default: true
     */
    @Builder.Default
    private boolean enableParallelProcessing = true;
    
    /**
     * Number of threads to use for parallel processing
     * Default: 2 (one for headers, one for bodies)
     */
    @Builder.Default
    private int processingThreads = 2;
    
    /**
     * Create a default configuration optimized for client applications
     */
    public static PipelineConfig defaultClientConfig() {
        return PipelineConfig.builder()
                .headerPipelineDepth(50)
                .bodyBatchSize(10)
                .maxParallelBodies(3)
                .enableParallelProcessing(true)
                .build();
    }
    
    /**
     * Create a configuration optimized for node implementations (high performance)
     */
    public static PipelineConfig highPerformanceNodeConfig() {
        return PipelineConfig.builder()
                .headerPipelineDepth(200)
                .bodyBatchSize(50)
                .maxParallelBodies(10)
                .headerBufferSize(1000)
                .enableParallelProcessing(true)
                .processingThreads(4)
                .build();
    }
    
    /**
     * Create a configuration for low-resource environments
     */
    public static PipelineConfig lowResourceConfig() {
        return PipelineConfig.builder()
                .headerPipelineDepth(20)
                .bodyBatchSize(5)
                .maxParallelBodies(2)
                .enableParallelProcessing(false)
                .processingThreads(1)
                .build();
    }
    
    /**
     * Validate the configuration and ensure sensible values
     */
    public void validate() {
        if (headerPipelineDepth <= 0) {
            throw new IllegalArgumentException("headerPipelineDepth must be positive");
        }
        if (bodyBatchSize <= 0) {
            throw new IllegalArgumentException("bodyBatchSize must be positive");
        }
        if (maxParallelBodies <= 0) {
            throw new IllegalArgumentException("maxParallelBodies must be positive");
        }
        if (headerBufferSize < headerPipelineDepth) {
            throw new IllegalArgumentException("headerBufferSize should be >= headerPipelineDepth");
        }
        if (processingThreads <= 0) {
            throw new IllegalArgumentException("processingThreads must be positive");
        }
        if (batchTimeout.isNegative() || batchTimeout.isZero()) {
            throw new IllegalArgumentException("batchTimeout must be positive");
        }
    }
}