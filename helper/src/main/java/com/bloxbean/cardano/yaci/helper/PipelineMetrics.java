package com.bloxbean.cardano.yaci.helper;

import lombok.Data;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Metrics for monitoring pipelining performance
 */
@Data
public class PipelineMetrics {
    
    // Header metrics
    private final AtomicLong headersReceived = new AtomicLong(0);
    private final AtomicLong headersProcessed = new AtomicLong(0);
    private final AtomicInteger headersInPipeline = new AtomicInteger(0);
    private final AtomicInteger headersPendingBodyFetch = new AtomicInteger(0);
    
    // Body metrics  
    private final AtomicLong bodiesRequested = new AtomicLong(0);
    private final AtomicLong bodiesReceived = new AtomicLong(0);
    private final AtomicLong bodiesProcessed = new AtomicLong(0);
    private final AtomicInteger activeBatchRequests = new AtomicInteger(0);
    
    // Performance metrics
    private final AtomicLong totalBytesReceived = new AtomicLong(0);
    private final AtomicLong avgHeaderProcessingTime = new AtomicLong(0);
    private final AtomicLong avgBodyProcessingTime = new AtomicLong(0);
    
    // Pipeline efficiency
    private final AtomicInteger maxHeadersInPipeline = new AtomicInteger(0);
    private final AtomicInteger maxActiveBatches = new AtomicInteger(0);
    private volatile Instant startTime = Instant.now();
    private volatile Instant lastActivity = Instant.now();
    
    // Error tracking
    private final AtomicLong headerErrors = new AtomicLong(0);
    private final AtomicLong bodyErrors = new AtomicLong(0);
    private final AtomicLong timeouts = new AtomicLong(0);
    
    /**
     * Record a received header
     */
    public void recordHeaderReceived() {
        headersReceived.incrementAndGet();
        headersInPipeline.incrementAndGet();
        updateMaxHeadersInPipeline();
        updateLastActivity();
    }
    
    /**
     * Record a processed header
     */
    public void recordHeaderProcessed() {
        headersProcessed.incrementAndGet();
        headersInPipeline.decrementAndGet();
        updateLastActivity();
    }
    
    /**
     * Record header queued for body fetch
     */
    public void recordHeaderQueuedForBodyFetch() {
        headersPendingBodyFetch.incrementAndGet();
    }
    
    /**
     * Record body request batch sent
     */
    public void recordBodyBatchRequested(int batchSize) {
        bodiesRequested.addAndGet(batchSize);
        activeBatchRequests.incrementAndGet();
        updateMaxActiveBatches();
        updateLastActivity();
    }
    
    /**
     * Record body received
     */
    public void recordBodyReceived(int bodySize) {
        bodiesReceived.incrementAndGet();
        totalBytesReceived.addAndGet(bodySize);
        headersPendingBodyFetch.decrementAndGet();
        updateLastActivity();
    }
    
    /**
     * Record body batch completed
     */
    public void recordBodyBatchCompleted() {
        activeBatchRequests.decrementAndGet();
        updateLastActivity();
    }
    
    /**
     * Record body processed
     */
    public void recordBodyProcessed() {
        bodiesProcessed.incrementAndGet();
        updateLastActivity();
    }
    
    /**
     * Record an error
     */
    public void recordError(ErrorType errorType) {
        switch (errorType) {
            case HEADER_ERROR:
                headerErrors.incrementAndGet();
                break;
            case BODY_ERROR:
                bodyErrors.incrementAndGet();
                break;
            case TIMEOUT:
                timeouts.incrementAndGet();
                break;
        }
        updateLastActivity();
    }
    
    /**
     * Calculate headers per second
     */
    public double getHeadersPerSecond() {
        long durationSeconds = getDurationSeconds();
        return durationSeconds > 0 ? (double) headersReceived.get() / durationSeconds : 0;
    }
    
    /**
     * Calculate bodies per second
     */
    public double getBodiesPerSecond() {
        long durationSeconds = getDurationSeconds();
        return durationSeconds > 0 ? (double) bodiesReceived.get() / durationSeconds : 0;
    }
    
    /**
     * Calculate bytes per second
     */
    public double getBytesPerSecond() {
        long durationSeconds = getDurationSeconds();
        return durationSeconds > 0 ? (double) totalBytesReceived.get() / durationSeconds : 0;
    }
    
    /**
     * Calculate pipeline efficiency (how well we're utilizing the pipeline)
     */
    public double getPipelineEfficiency() {
        long total = headersReceived.get();
        return total > 0 ? (double) headersProcessed.get() / total : 0;
    }
    
    /**
     * Calculate error rate
     */
    public double getErrorRate() {
        long total = headersReceived.get() + bodiesReceived.get();
        long errors = headerErrors.get() + bodyErrors.get() + timeouts.get();
        return total > 0 ? (double) errors / total : 0;
    }
    
    /**
     * Reset all metrics
     */
    public void reset() {
        headersReceived.set(0);
        headersProcessed.set(0);
        headersInPipeline.set(0);
        headersPendingBodyFetch.set(0);
        bodiesRequested.set(0);
        bodiesReceived.set(0);
        bodiesProcessed.set(0);
        activeBatchRequests.set(0);
        totalBytesReceived.set(0);
        avgHeaderProcessingTime.set(0);
        avgBodyProcessingTime.set(0);
        maxHeadersInPipeline.set(0);
        maxActiveBatches.set(0);
        headerErrors.set(0);
        bodyErrors.set(0);
        timeouts.set(0);
        startTime = Instant.now();
        lastActivity = Instant.now();
    }
    
    /**
     * Get a summary of current metrics
     */
    public String getSummary() {
        return String.format(
            "Pipeline Metrics: Headers: %d/%d (%.1f/s), Bodies: %d/%d (%.1f/s), " +
            "Pipeline: %d/%d, Batches: %d, Efficiency: %.2f%%, Errors: %.2f%%",
            headersProcessed.get(), headersReceived.get(), getHeadersPerSecond(),
            bodiesProcessed.get(), bodiesReceived.get(), getBodiesPerSecond(),
            headersInPipeline.get(), maxHeadersInPipeline.get(),
            activeBatchRequests.get(),
            getPipelineEfficiency() * 100,
            getErrorRate() * 100
        );
    }
    
    private void updateMaxHeadersInPipeline() {
        int current = headersInPipeline.get();
        int max = maxHeadersInPipeline.get();
        if (current > max) {
            maxHeadersInPipeline.compareAndSet(max, current);
        }
    }
    
    private void updateMaxActiveBatches() {
        int current = activeBatchRequests.get();
        int max = maxActiveBatches.get();
        if (current > max) {
            maxActiveBatches.compareAndSet(max, current);
        }
    }
    
    private void updateLastActivity() {
        lastActivity = Instant.now();
    }
    
    private long getDurationSeconds() {
        return Instant.now().getEpochSecond() - startTime.getEpochSecond();
    }
    
    public enum ErrorType {
        HEADER_ERROR,
        BODY_ERROR,
        TIMEOUT
    }
}