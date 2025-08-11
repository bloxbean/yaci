package com.bloxbean.cardano.yaci.core.protocol.chainsync.pipeline;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Comprehensive error recovery system for ChainSync pipeline operations.
 * 
 * This class provides sophisticated error handling and recovery mechanisms
 * for various failure scenarios that can occur during pipelined ChainSync operations.
 * It implements exponential backoff, circuit breaker patterns, and intelligent
 * recovery strategies to maintain sync robustness.
 */
@Slf4j
public class PipelineErrorRecovery {
    
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong totalFailures = new AtomicLong(0);
    private final AtomicLong totalRecoveries = new AtomicLong(0);
    
    private volatile Instant lastFailureTime = null;
    private volatile Instant circuitBreakerOpenTime = null;
    private volatile ErrorRecoveryState state = ErrorRecoveryState.HEALTHY;
    private volatile Point lastKnownGoodPoint = null;
    
    private final ErrorRecoveryConfig config;
    
    @Builder
    @Data
    public static class ErrorRecoveryConfig {
        @Builder.Default
        private int maxConsecutiveFailures = 5;
        
        @Builder.Default
        private Duration initialBackoffDelay = Duration.ofSeconds(1);
        
        @Builder.Default
        private Duration maxBackoffDelay = Duration.ofMinutes(5);
        
        @Builder.Default
        private double backoffMultiplier = 2.0;
        
        @Builder.Default
        private Duration circuitBreakerTimeout = Duration.ofMinutes(10);
        
        @Builder.Default
        private int healthCheckInterval = 3; // Check every N failures
        
        @Builder.Default
        private boolean enableCircuitBreaker = true;
        
        @Builder.Default
        private boolean enableExponentialBackoff = true;
        
        public static ErrorRecoveryConfig defaultConfig() {
            return ErrorRecoveryConfig.builder().build();
        }
        
        public static ErrorRecoveryConfig conservativeConfig() {
            return ErrorRecoveryConfig.builder()
                    .maxConsecutiveFailures(3)
                    .initialBackoffDelay(Duration.ofSeconds(2))
                    .maxBackoffDelay(Duration.ofMinutes(10))
                    .backoffMultiplier(3.0)
                    .circuitBreakerTimeout(Duration.ofMinutes(15))
                    .build();
        }
        
        public static ErrorRecoveryConfig aggressiveConfig() {
            return ErrorRecoveryConfig.builder()
                    .maxConsecutiveFailures(10)
                    .initialBackoffDelay(Duration.ofMillis(500))
                    .maxBackoffDelay(Duration.ofMinutes(2))
                    .backoffMultiplier(1.5)
                    .circuitBreakerTimeout(Duration.ofMinutes(5))
                    .build();
        }
    }
    
    public enum ErrorRecoveryState {
        HEALTHY,           // Normal operation
        DEGRADED,          // Experiencing some failures but still operational
        CIRCUIT_OPEN,      // Circuit breaker open, rejecting requests
        RECOVERING         // Attempting to recover from failures
    }
    
    public enum ErrorType {
        CONNECTION_FAILURE,    // Network connection lost
        TIMEOUT,              // Request timeout
        PROTOCOL_ERROR,       // Protocol-level error
        CHAIN_REORG,          // Chain reorganization detected  
        RESOURCE_EXHAUSTION,  // Memory or other resource issues
        UNKNOWN               // Unclassified error
    }
    
    @Data
    @Builder
    public static class RecoveryAction {
        private final RecoveryStrategy strategy;
        private final Duration backoffDelay;
        private final String reason;
        private final boolean shouldResetPipeline;
        private final Point recoveryPoint;
    }
    
    public enum RecoveryStrategy {
        IMMEDIATE_RETRY,      // Retry immediately
        BACKOFF_RETRY,        // Wait and retry with exponential backoff
        RESET_PIPELINE,       // Reset pipeline and start over
        FALLBACK_SEQUENTIAL,  // Disable pipelining temporarily
        CIRCUIT_BREAK,        // Open circuit breaker
        RECONNECT            // Full reconnection required
    }
    
    public PipelineErrorRecovery() {
        this(ErrorRecoveryConfig.defaultConfig());
    }
    
    public PipelineErrorRecovery(ErrorRecoveryConfig config) {
        this.config = config;
        if (log.isDebugEnabled()) {
            log.debug("🔧 Error recovery initialized with config: max failures={}, initial backoff={}ms",
                     config.maxConsecutiveFailures, config.initialBackoffDelay.toMillis());
        }
    }
    
    /**
     * Handle a pipeline error and determine the appropriate recovery action.
     */
    public RecoveryAction handleError(ErrorType errorType, Throwable cause, 
                                     Point currentPoint, int outstandingRequests) {
        totalFailures.incrementAndGet();
        int consecutive = consecutiveFailures.incrementAndGet();
        lastFailureTime = Instant.now();
        
        if (log.isWarnEnabled()) {
            log.warn("⚠️ Pipeline error: {} (consecutive: {}, total: {}) - {}", 
                     errorType, consecutive, totalFailures.get(), 
                     cause != null ? cause.getMessage() : "unknown cause");
        }
        
        // Update state based on failure count
        updateRecoveryState(consecutive, errorType);
        
        // Determine recovery strategy
        RecoveryStrategy strategy = determineRecoveryStrategy(errorType, consecutive, outstandingRequests);
        Duration backoffDelay = calculateBackoffDelay(consecutive);
        boolean shouldReset = shouldResetPipeline(errorType, consecutive);
        Point recoveryPoint = determineRecoveryPoint(errorType, currentPoint);
        
        String reason = formatRecoveryReason(errorType, strategy, consecutive);
        
        if (log.isInfoEnabled()) {
            log.info("🛠 Recovery action: {} (delay={}ms, reset={}, point={}) - {}",
                     strategy, backoffDelay.toMillis(), shouldReset, 
                     recoveryPoint != null ? recoveryPoint.getSlot() : "none", reason);
        }
        
        return RecoveryAction.builder()
                .strategy(strategy)
                .backoffDelay(backoffDelay)
                .reason(reason)
                .shouldResetPipeline(shouldReset)
                .recoveryPoint(recoveryPoint)
                .build();
    }
    
    /**
     * Record a successful operation to reset failure counters.
     */
    public void recordSuccess(Point currentPoint) {
        int previousFailures = consecutiveFailures.getAndSet(0);
        if (previousFailures > 0) {
            totalRecoveries.incrementAndGet();
            if (log.isInfoEnabled()) {
                log.info("✅ Pipeline recovered after {} consecutive failures", previousFailures);
            }
        }
        
        // Update state and last known good point
        state = ErrorRecoveryState.HEALTHY;
        circuitBreakerOpenTime = null;
        lastKnownGoodPoint = currentPoint;
    }
    
    /**
     * Check if the circuit breaker is open.
     */
    public boolean isCircuitOpen() {
        if (!config.enableCircuitBreaker || state != ErrorRecoveryState.CIRCUIT_OPEN) {
            return false;
        }
        
        if (circuitBreakerOpenTime != null && 
            Duration.between(circuitBreakerOpenTime, Instant.now()).compareTo(config.circuitBreakerTimeout) > 0) {
            // Circuit breaker timeout expired, try to recover
            state = ErrorRecoveryState.RECOVERING;
            circuitBreakerOpenTime = null;
            if (log.isInfoEnabled()) {
                log.info("♾️ Circuit breaker timeout expired, attempting recovery");
            }
            return false;
        }
        
        return true;
    }
    
    private void updateRecoveryState(int consecutiveFailures, ErrorType errorType) {
        if (consecutiveFailures >= config.maxConsecutiveFailures && config.enableCircuitBreaker) {
            state = ErrorRecoveryState.CIRCUIT_OPEN;
            circuitBreakerOpenTime = Instant.now();
            if (log.isErrorEnabled()) {
                log.error("🚫 Circuit breaker opened after {} consecutive failures", consecutiveFailures);
            }
        } else if (consecutiveFailures > config.maxConsecutiveFailures / 2) {
            state = ErrorRecoveryState.DEGRADED;
        } else if (state == ErrorRecoveryState.RECOVERING && consecutiveFailures < 2) {
            state = ErrorRecoveryState.HEALTHY;
        }
    }
    
    private RecoveryStrategy determineRecoveryStrategy(ErrorType errorType, int consecutiveFailures, int outstandingRequests) {
        // Circuit breaker takes precedence
        if (isCircuitOpen()) {
            return RecoveryStrategy.CIRCUIT_BREAK;
        }
        
        // Strategy based on error type
        switch (errorType) {
            case CONNECTION_FAILURE:
                if (consecutiveFailures >= 3) {
                    return RecoveryStrategy.RECONNECT;
                } else {
                    return RecoveryStrategy.BACKOFF_RETRY;
                }
                
            case TIMEOUT:
                if (outstandingRequests > 10) {
                    return RecoveryStrategy.FALLBACK_SEQUENTIAL;
                } else {
                    return RecoveryStrategy.BACKOFF_RETRY;
                }
                
            case CHAIN_REORG:
                return RecoveryStrategy.RESET_PIPELINE;
                
            case RESOURCE_EXHAUSTION:
                return RecoveryStrategy.FALLBACK_SEQUENTIAL;
                
            case PROTOCOL_ERROR:
                if (consecutiveFailures >= 5) {
                    return RecoveryStrategy.RECONNECT;
                } else {
                    return RecoveryStrategy.RESET_PIPELINE;
                }
                
            default:
                if (consecutiveFailures == 1) {
                    return RecoveryStrategy.IMMEDIATE_RETRY;
                } else {
                    return RecoveryStrategy.BACKOFF_RETRY;
                }
        }
    }
    
    private Duration calculateBackoffDelay(int consecutiveFailures) {
        if (!config.enableExponentialBackoff || consecutiveFailures <= 1) {
            return Duration.ZERO;
        }
        
        double delayMs = config.initialBackoffDelay.toMillis() * 
                        Math.pow(config.backoffMultiplier, Math.min(consecutiveFailures - 1, 10));
        
        long cappedDelayMs = Math.min((long) delayMs, config.maxBackoffDelay.toMillis());
        
        return Duration.ofMillis(cappedDelayMs);
    }
    
    private boolean shouldResetPipeline(ErrorType errorType, int consecutiveFailures) {
        return errorType == ErrorType.CHAIN_REORG || 
               errorType == ErrorType.PROTOCOL_ERROR ||
               consecutiveFailures >= config.maxConsecutiveFailures / 2;
    }
    
    private Point determineRecoveryPoint(ErrorType errorType, Point currentPoint) {
        if (errorType == ErrorType.CHAIN_REORG && lastKnownGoodPoint != null) {
            return lastKnownGoodPoint;
        }
        return currentPoint;
    }
    
    private String formatRecoveryReason(ErrorType errorType, RecoveryStrategy strategy, int consecutiveFailures) {
        return String.format("%s error after %d consecutive failures, using %s strategy", 
                           errorType, consecutiveFailures, strategy);
    }
    
    /**
     * Get current error recovery statistics.
     */
    public ErrorRecoveryStats getStats() {
        return ErrorRecoveryStats.builder()
                .state(state)
                .consecutiveFailures(consecutiveFailures.get())
                .totalFailures(totalFailures.get())
                .totalRecoveries(totalRecoveries.get())
                .lastFailureTime(lastFailureTime)
                .circuitBreakerOpen(isCircuitOpen())
                .lastKnownGoodPoint(lastKnownGoodPoint)
                .build();
    }
    
    @Data
    @Builder
    public static class ErrorRecoveryStats {
        private final ErrorRecoveryState state;
        private final int consecutiveFailures;
        private final long totalFailures;
        private final long totalRecoveries;
        private final Instant lastFailureTime;
        private final boolean circuitBreakerOpen;
        private final Point lastKnownGoodPoint;
        
        public String getSummary() {
            return String.format(
                "Recovery Stats: state=%s, consecutive=%d, total=%d/%d, circuit=%s, last_good=slot_%s",
                state, consecutiveFailures, totalFailures, totalRecoveries, 
                circuitBreakerOpen ? "OPEN" : "CLOSED",
                lastKnownGoodPoint != null ? lastKnownGoodPoint.getSlot() : "none"
            );
        }
    }
    
    /**
     * Reset all error recovery state.
     */
    public void reset() {
        consecutiveFailures.set(0);
        totalFailures.set(0);
        totalRecoveries.set(0);
        state = ErrorRecoveryState.HEALTHY;
        lastFailureTime = null;
        circuitBreakerOpenTime = null;
        lastKnownGoodPoint = null;
        
        if (log.isDebugEnabled()) {
            log.debug("🔄 Error recovery state reset");
        }
    }
}