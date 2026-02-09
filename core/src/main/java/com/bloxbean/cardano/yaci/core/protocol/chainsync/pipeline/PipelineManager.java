package com.bloxbean.cardano.yaci.core.protocol.chainsync.pipeline;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.RequestNext;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Advanced pipeline manager that integrates decision strategies with ChainSync protocol.
 * 
 * This manager coordinates between the ChainSync agent and pipeline decision strategies
 * to provide intelligent, adaptive pipeline behavior. It tracks outstanding requests,
 * manages pipeline state, and provides comprehensive metrics.
 */
@Slf4j
public class PipelineManager {
    
    @Getter
    private final PipelineDecisionStrategy strategy;
    
    @Getter
    private final PipelineMetrics metrics;
    
    @Getter
    private final PipelineErrorRecovery errorRecovery;
    
    private final AtomicInteger outstandingRequests = new AtomicInteger(0);
    private final Map<String, PendingRequest> pendingRequests = new ConcurrentHashMap<>();
    
    private volatile Point currentClientTip;
    private volatile Point currentServerTip;
    private volatile boolean enabled = true;
    
    public PipelineManager(PipelineDecisionStrategy strategy) {
        this(strategy, PipelineErrorRecovery.ErrorRecoveryConfig.defaultConfig());
    }
    
    public PipelineManager(PipelineDecisionStrategy strategy, PipelineErrorRecovery.ErrorRecoveryConfig errorConfig) {
        this.strategy = strategy;
        this.metrics = new PipelineMetrics();
        this.errorRecovery = new PipelineErrorRecovery(errorConfig);
        
        if (log.isDebugEnabled()) {
            log.debug("🚀 Pipeline manager initialized with strategy: {}", strategy.getStrategyName());
        }
    }
    
    /**
     * Determine the next pipeline action based on current state.
     */
    public PipelineAction getNextAction() {
        if (!enabled) {
            return new PipelineAction(PipelineDecision.REQUEST, 1, "Pipeline disabled");
        }
        
        // Check if circuit breaker is open
        if (errorRecovery.isCircuitOpen()) {
            return new PipelineAction(PipelineDecision.REQUEST, 1, "Circuit breaker open - using sequential requests");
        }
        
        int outstanding = outstandingRequests.get();
        long clientSlot = currentClientTip != null ? currentClientTip.getSlot() : 0;
        long serverSlot = currentServerTip != null ? currentServerTip.getSlot() : 0;
        
        NetworkMetrics networkMetrics = metrics.toNetworkMetrics();
        PipelineDecision decision = strategy.decide(outstanding, clientSlot, serverSlot, networkMetrics);
        
        metrics.recordDecision(decision);
        
        // Determine how many requests to send based on decision
        int requestCount = calculateRequestCount(decision, outstanding, clientSlot, serverSlot);
        String reason = formatDecisionReason(decision, outstanding, clientSlot, serverSlot);
        
        if (log.isDebugEnabled()) {
            log.debug("🎯 Pipeline decision: {} → {} requests (outstanding: {}, client: {}, server: {}) - {}",
                     decision, requestCount, outstanding, clientSlot, serverSlot, reason);
        }
        
        return new PipelineAction(decision, requestCount, reason);
    }
    
    private int calculateRequestCount(PipelineDecision decision, int outstanding, long clientSlot, long serverSlot) {
        switch (decision) {
            case REQUEST:
                return 1; // Single non-pipelined request
                
            case PIPELINE:
                // Calculate how many to pipeline based on distance to server tip
                long slotsBehind = Math.max(0, serverSlot - clientSlot);
                int maxDepth = strategy.getMaxPipelineDepth();
                int available = maxDepth - outstanding;
                return Math.min(available, Math.min(10, (int) slotsBehind)); // Cap at 10 per batch
                
            case COLLECT_OR_PIPELINE:
                // Adaptive: collect 1, then maybe pipeline 1-3 more
                return outstanding > 0 ? 0 : Math.min(3, strategy.getMaxPipelineDepth() - outstanding);
                
            case COLLECT:
                return 0; // Just collect, don't send new requests
                
            default:
                return 1;
        }
    }
    
    private String formatDecisionReason(PipelineDecision decision, int outstanding, long clientSlot, long serverSlot) {
        long behind = Math.max(0, serverSlot - clientSlot);
        int maxDepth = strategy.getMaxPipelineDepth();
        
        switch (decision) {
            case REQUEST:
                if (outstanding == 0 && behind == 0) {
                    return "synchronized with server tip";
                } else if (outstanding > 0) {
                    return "collecting before non-pipelined request";
                } else {
                    return "starting with non-pipelined request";
                }
                
            case PIPELINE:
                return String.format("behind server by %d slots, capacity %d/%d", behind, outstanding, maxDepth);
                
            case COLLECT_OR_PIPELINE:
                return String.format("adaptive mode: %d outstanding, %d slots behind", outstanding, behind);
                
            case COLLECT:
                if (outstanding >= maxDepth) {
                    return String.format("pipeline full (%d/%d)", outstanding, maxDepth);
                } else if (outstanding >= behind) {
                    return String.format("approaching server tip (%d outstanding >= %d behind)", outstanding, behind);
                } else {
                    return "strategy requires collection";
                }
                
            default:
                return "unknown reason";
        }
    }
    
    /**
     * Record that a request has been sent.
     */
    public void recordRequestSent(RequestNext request, boolean isPipelined) {
        if (isPipelined) {
            String requestId = generateRequestId();
            PendingRequest pendingRequest = new PendingRequest(requestId, Instant.now(), request);
            pendingRequests.put(requestId, pendingRequest);
            outstandingRequests.incrementAndGet();
        }
        
        metrics.recordRequestSent(isPipelined);
        
        if (log.isDebugEnabled()) {
            log.debug("📤 Request sent: pipelined={}, outstanding={}/{}", 
                     isPipelined, outstandingRequests.get(), strategy.getMaxPipelineDepth());
        }
    }
    
    /**
     * Record that a response has been received.
     */
    public void recordResponseReceived(boolean success, long blockSize) {
        if (outstandingRequests.get() > 0) {
            // Find and remove the oldest pending request
            PendingRequest completed = removeOldestPendingRequest();
            if (completed != null) {
                long responseTime = completed.getResponseTimeMs();
                metrics.recordResponseCollected(responseTime, success);
                outstandingRequests.decrementAndGet();
                
                // Notify strategy about completion
                if (success) {
                    strategy.onRequestCompleted(responseTime);
                    errorRecovery.recordSuccess(currentClientTip);
                } else {
                    strategy.onRequestFailed(new RuntimeException("Request failed"));
                }
                
                if (log.isTraceEnabled()) {
                    log.trace("📥 Response received: success={}, time={}ms, outstanding={}", 
                             success, responseTime, outstandingRequests.get());
                }
            }
        }
        
        if (success && blockSize > 0) {
            metrics.recordBlockReceived(blockSize);
        }
    }
    
    private PendingRequest removeOldestPendingRequest() {
        if (pendingRequests.isEmpty()) return null;
        
        String oldestId = pendingRequests.keySet().stream()
                .min((id1, id2) -> pendingRequests.get(id1).timestamp.compareTo(pendingRequests.get(id2).timestamp))
                .orElse(null);
                
        return oldestId != null ? pendingRequests.remove(oldestId) : null;
    }
    
    private String generateRequestId() {
        return "req_" + System.nanoTime();
    }
    
    /**
     * Update client tip position.
     */
    public void updateClientTip(Point clientTip) {
        this.currentClientTip = clientTip;
        if (log.isTraceEnabled()) {
            log.trace("📍 Client tip updated: slot={}", clientTip != null ? clientTip.getSlot() : null);
        }
    }
    
    /**
     * Update server tip position.
     */
    public void updateServerTip(Point serverTip) {
        this.currentServerTip = serverTip;
        if (log.isTraceEnabled()) {
            log.trace("🏔 Server tip updated: slot={}", serverTip != null ? serverTip.getSlot() : null);
        }
    }
    
    /**
     * Enable or disable pipelining.
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (log.isDebugEnabled()) {
            log.debug("🔧 Pipeline {}", enabled ? "enabled" : "disabled");
        }
    }
    
    /**
     * Get current outstanding request count.
     */
    public int getOutstandingRequestCount() {
        return outstandingRequests.get();
    }
    
    /**
     * Check if pipeline is currently full.
     */
    public boolean isPipelineFull() {
        return outstandingRequests.get() >= strategy.getMaxPipelineDepth();
    }
    
    /**
     * Handle a pipeline error and get recovery action.
     */
    public PipelineErrorRecovery.RecoveryAction handleError(PipelineErrorRecovery.ErrorType errorType, 
                                                           Throwable cause) {
        return errorRecovery.handleError(errorType, cause, currentClientTip, outstandingRequests.get());
    }
    
    /**
     * Apply a recovery action to the pipeline state.
     */
    public void applyRecoveryAction(PipelineErrorRecovery.RecoveryAction action) {
        if (log.isInfoEnabled()) {
            log.info("🛠 Applying recovery action: {} - {}", action.getStrategy(), action.getReason());
        }
        
        switch (action.getStrategy()) {
            case RESET_PIPELINE:
                if (action.isShouldResetPipeline()) {
                    pendingRequests.clear();
                    outstandingRequests.set(0);
                    if (action.getRecoveryPoint() != null) {
                        currentClientTip = action.getRecoveryPoint();
                    }
                }
                break;
                
            case FALLBACK_SEQUENTIAL:
                // Temporarily disable pipelining by clearing outstanding requests
                pendingRequests.clear();
                outstandingRequests.set(0);
                enabled = false;
                if (log.isInfoEnabled()) {
                    log.info("🔄 Falling back to sequential mode temporarily");
                }
                break;
                
            case CIRCUIT_BREAK:
                // Pipeline manager will check circuit breaker in getNextAction()
                if (log.isWarnEnabled()) {
                    log.warn("⛔ Circuit breaker activated - rejecting pipeline requests");
                }
                break;
                
            case RECONNECT:
                // Full reset including state
                reset();
                if (log.isWarnEnabled()) {
                    log.warn("🔌 Full reconnection required - pipeline reset");
                }
                break;
                
            default:
                // For IMMEDIATE_RETRY and BACKOFF_RETRY, just log
                if (log.isDebugEnabled()) {
                    log.debug("⏳ Retry strategy: {} with delay {}ms", 
                             action.getStrategy(), action.getBackoffDelay().toMillis());
                }
                break;
        }
    }
    
    /**
     * Reset pipeline state and metrics.
     */
    public void reset() {
        outstandingRequests.set(0);
        pendingRequests.clear();
        currentClientTip = null;
        currentServerTip = null;
        metrics.reset();
        errorRecovery.reset();
        enabled = true; // Re-enable pipeline after reset
        
        if (log.isDebugEnabled()) {
            log.debug("🔄 Pipeline manager reset");
        }
    }
    
    /**
     * Log current pipeline statistics.
     */
    public void logStatistics() {
        metrics.logStats();
        PipelineErrorRecovery.ErrorRecoveryStats errorStats = errorRecovery.getStats();
        if (log.isInfoEnabled()) {
            log.info("🔧 {}", errorStats.getSummary());
        }
    }
    
    /**
     * Represents a pipeline action decision.
     */
    @Getter
    public static class PipelineAction {
        private final PipelineDecision decision;
        private final int requestCount;
        private final String reason;
        
        public PipelineAction(PipelineDecision decision, int requestCount, String reason) {
            this.decision = decision;
            this.requestCount = requestCount;
            this.reason = reason;
        }
        
        public boolean shouldSendRequests() {
            return requestCount > 0;
        }
        
        public boolean shouldCollect() {
            return decision == PipelineDecision.COLLECT || 
                   decision == PipelineDecision.COLLECT_OR_PIPELINE;
        }
        
        public boolean isPipelined() {
            return decision == PipelineDecision.PIPELINE || 
                   decision == PipelineDecision.COLLECT_OR_PIPELINE;
        }
        
        @Override
        public String toString() {
            return String.format("PipelineAction{decision=%s, count=%d, reason='%s'}", 
                               decision, requestCount, reason);
        }
    }
    
    /**
     * Represents a pending pipelined request.
     */
    private static class PendingRequest {
        private final String id;
        private final Instant timestamp;
        private final RequestNext request;
        
        public PendingRequest(String id, Instant timestamp, RequestNext request) {
            this.id = id;
            this.timestamp = timestamp;
            this.request = request;
        }
        
        public long getResponseTimeMs() {
            return Instant.now().toEpochMilli() - timestamp.toEpochMilli();
        }
    }
}