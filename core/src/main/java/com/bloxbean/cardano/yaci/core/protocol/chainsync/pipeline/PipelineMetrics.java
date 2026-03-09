package com.bloxbean.cardano.yaci.core.protocol.chainsync.pipeline;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/**
 * Comprehensive metrics collector for ChainSync pipeline performance.
 * 
 * This class tracks detailed statistics about pipeline behavior to help
 * optimize strategy selection and diagnose performance issues.
 * Thread-safe for use in concurrent environments.
 */
@Slf4j
@Getter
public class PipelineMetrics {
    
    // Request counters
    private final LongAdder totalRequests = new LongAdder();
    private final LongAdder pipelinedRequests = new LongAdder();
    private final LongAdder sequentialRequests = new LongAdder();
    private final LongAdder collectedRequests = new LongAdder();
    
    // Success/failure tracking
    private final LongAdder successfulRequests = new LongAdder();
    private final LongAdder failedRequests = new LongAdder();
    
    // Timing statistics
    private final LongAccumulator minResponseTime = new LongAccumulator(Long::min, Long.MAX_VALUE);
    private final LongAccumulator maxResponseTime = new LongAccumulator(Long::max, 0L);
    private final LongAdder totalResponseTime = new LongAdder();
    
    // Pipeline depth tracking
    private final LongAccumulator maxPipelineDepth = new LongAccumulator(Long::max, 0L);
    private final AtomicLong currentPipelineDepth = new AtomicLong(0);
    
    // Strategy switch tracking
    private final LongAdder strategyDecisionCount = new LongAdder();
    private final LongAdder requestDecisions = new LongAdder();
    private final LongAdder pipelineDecisions = new LongAdder();
    private final LongAdder collectDecisions = new LongAdder();
    private final LongAdder collectOrPipelineDecisions = new LongAdder();
    
    // Efficiency metrics
    private final LongAdder blocksReceived = new LongAdder();
    private final LongAdder bytesReceived = new LongAdder();
    private final Instant startTime = Instant.now();
    
    /**
     * Record a pipeline decision being made.
     */
    public void recordDecision(PipelineDecision decision) {
        strategyDecisionCount.increment();
        
        switch (decision) {
            case REQUEST:
                requestDecisions.increment();
                break;
            case PIPELINE:
                pipelineDecisions.increment();
                break;
            case COLLECT:
                collectDecisions.increment();
                break;
            case COLLECT_OR_PIPELINE:
                collectOrPipelineDecisions.increment();
                break;
        }
        
        if (log.isTraceEnabled()) {
            log.trace("Pipeline decision: {} (total: {})", decision, strategyDecisionCount.sum());
        }
    }
    
    /**
     * Record a request being sent.
     */
    public void recordRequestSent(boolean isPipelined) {
        totalRequests.increment();
        
        if (isPipelined) {
            pipelinedRequests.increment();
            long newDepth = currentPipelineDepth.incrementAndGet();
            maxPipelineDepth.accumulate(newDepth);
        } else {
            sequentialRequests.increment();
        }
        
        if (log.isTraceEnabled()) {
            log.trace("Request sent: pipelined={}, current depth={}", isPipelined, currentPipelineDepth.get());
        }
    }
    
    /**
     * Record a response being collected.
     */
    public void recordResponseCollected(long responseTimeMs, boolean success) {
        collectedRequests.increment();
        currentPipelineDepth.decrementAndGet();
        
        if (success) {
            successfulRequests.increment();
            minResponseTime.accumulate(responseTimeMs);
            maxResponseTime.accumulate(responseTimeMs);
            totalResponseTime.add(responseTimeMs);
        } else {
            failedRequests.increment();
        }
        
        if (log.isTraceEnabled()) {
            log.trace("Response collected: success={}, time={}ms, current depth={}", 
                     success, responseTimeMs, currentPipelineDepth.get());
        }
    }
    
    /**
     * Record block data received.
     */
    public void recordBlockReceived(long blockSize) {
        blocksReceived.increment();
        bytesReceived.add(blockSize);
    }
    
    /**
     * Get current pipeline efficiency (successful requests / total requests).
     */
    public double getPipelineEfficiency() {
        long total = totalRequests.sum();
        if (total == 0) return 1.0;
        return (double) successfulRequests.sum() / total;
    }
    
    /**
     * Get average response time in milliseconds.
     */
    public double getAverageResponseTime() {
        long successful = successfulRequests.sum();
        if (successful == 0) return 0.0;
        return (double) totalResponseTime.sum() / successful;
    }
    
    /**
     * Get current throughput in blocks per second.
     */
    public double getBlocksPerSecond() {
        Duration elapsed = Duration.between(startTime, Instant.now());
        if (elapsed.isZero()) return 0.0;
        return (double) blocksReceived.sum() / elapsed.toSeconds();
    }
    
    /**
     * Get current bandwidth in bytes per second.
     */
    public double getBytesPerSecond() {
        Duration elapsed = Duration.between(startTime, Instant.now());
        if (elapsed.isZero()) return 0.0;
        return (double) bytesReceived.sum() / elapsed.toSeconds();
    }
    
    /**
     * Get pipeline utilization ratio (pipelined requests / total requests).
     */
    public double getPipelineUtilization() {
        long total = totalRequests.sum();
        if (total == 0) return 0.0;
        return (double) pipelinedRequests.sum() / total;
    }
    
    /**
     * Reset all metrics to initial values.
     */
    public synchronized void reset() {
        totalRequests.reset();
        pipelinedRequests.reset();
        sequentialRequests.reset();
        collectedRequests.reset();
        
        successfulRequests.reset();
        failedRequests.reset();
        
        minResponseTime.reset();
        maxResponseTime.reset();
        totalResponseTime.reset();
        
        maxPipelineDepth.reset();
        currentPipelineDepth.set(0);
        
        strategyDecisionCount.reset();
        requestDecisions.reset();
        pipelineDecisions.reset();
        collectDecisions.reset();
        collectOrPipelineDecisions.reset();
        
        blocksReceived.reset();
        bytesReceived.reset();
        
        if (log.isDebugEnabled()) {
            log.debug("Pipeline metrics reset");
        }
    }
    
    /**
     * Generate a comprehensive statistics summary.
     */
    public String getStatsSummary() {
        Duration uptime = Duration.between(startTime, Instant.now());
        
        return String.format(
            "Pipeline Stats: " +
            "requests=%d (pipelined=%d, sequential=%d), " +
            "success/fail=%d/%d (%.1f%%), " +
            "response_time=%.1fms (min=%d, max=%d), " +
            "pipeline_depth=%d (max=%d), " +
            "throughput=%.1f blocks/s (%.1f KB/s), " +
            "decisions: REQUEST=%d, PIPELINE=%d, COLLECT=%d, COLLECT_OR_PIPELINE=%d, " +
            "uptime=%ds",
            
            totalRequests.sum(), pipelinedRequests.sum(), sequentialRequests.sum(),
            successfulRequests.sum(), failedRequests.sum(), getPipelineEfficiency() * 100,
            getAverageResponseTime(), 
            minResponseTime.get() == Long.MAX_VALUE ? 0 : minResponseTime.get(), 
            maxResponseTime.get(),
            currentPipelineDepth.get(), maxPipelineDepth.get(),
            getBlocksPerSecond(), getBytesPerSecond() / 1024,
            requestDecisions.sum(), pipelineDecisions.sum(), 
            collectDecisions.sum(), collectOrPipelineDecisions.sum(),
            uptime.toSeconds()
        );
    }
    
    /**
     * Log current statistics at INFO level.
     */
    public void logStats() {
        if (log.isInfoEnabled()) {
            log.info("📊 {}", getStatsSummary());
        }
    }
    
    /**
     * Create a NetworkMetrics snapshot from current pipeline performance.
     */
    public NetworkMetrics toNetworkMetrics() {
        return NetworkMetrics.builder()
                .avgResponseTimeMs((long) getAverageResponseTime())
                .pipelineEfficiency(getPipelineEfficiency())
                .networkInstability(getPipelineEfficiency() < 0.8)
                .memoryPressure((int) Math.min(100, currentPipelineDepth.get() * 2)) // Rough estimate
                .build();
    }
}