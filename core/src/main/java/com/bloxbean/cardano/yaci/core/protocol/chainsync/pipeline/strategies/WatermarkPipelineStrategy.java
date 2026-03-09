package com.bloxbean.cardano.yaci.core.protocol.chainsync.pipeline.strategies;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.pipeline.NetworkMetrics;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.pipeline.PipelineDecision;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.pipeline.PipelineDecisionStrategy;
import lombok.extern.slf4j.Slf4j;

/**
 * Watermark-based pipelining strategy with low and high marks.
 * 
 * This strategy is based on the Haskell implementation's `pipelineDecisionLowHighMark`
 * and provides adaptive pipelining behavior. It operates in two modes:
 * 
 * - **Low Mode**: Pipelines up to high mark, uses COLLECT_OR_PIPELINE for efficiency
 * - **High Mode**: Collects down to low mark to reduce memory pressure
 * 
 * The strategy switches between modes based on the number of outstanding requests:
 * - When requests exceed high mark → switch to High Mode (collect eagerly)
 * - When requests drop below low mark → switch to Low Mode (pipeline more)
 * 
 * Best used for general-purpose applications where you want good throughput
 * with bounded memory usage and adaptive behavior.
 */
@Slf4j
public class WatermarkPipelineStrategy implements PipelineDecisionStrategy {
    
    private final int lowMark;
    private final int highMark;
    private boolean inHighMode = false; // Start in low mode
    
    public WatermarkPipelineStrategy(int lowMark, int highMark) {
        if (lowMark >= highMark) {
            throw new IllegalArgumentException("lowMark must be less than highMark");
        }
        this.lowMark = Math.max(1, lowMark);
        this.highMark = Math.max(lowMark + 1, highMark);
    }
    
    public WatermarkPipelineStrategy() {
        this(10, 50); // Conservative defaults
    }
    
    @Override
    public PipelineDecision decide(int outstandingRequests, 
                                  long clientTipSlot, 
                                  long serverTipSlot, 
                                  NetworkMetrics networkMetrics) {
        
        // Update mode based on current outstanding requests
        if (outstandingRequests >= highMark && !inHighMode) {
            inHighMode = true;
            if (log.isDebugEnabled()) {
                log.debug("Switching to HIGH mode (outstanding: {} >= high: {})", outstandingRequests, highMark);
            }
        } else if (outstandingRequests <= lowMark && inHighMode) {
            inHighMode = false;
            if (log.isDebugEnabled()) {
                log.debug("Switching to LOW mode (outstanding: {} <= low: {})", outstandingRequests, lowMark);
            }
        }
        
        // When no outstanding requests
        if (outstandingRequests == 0) {
            // If we're synchronized with server's tip, use non-pipelined requests
            if (clientTipSlot >= serverTipSlot) {
                if (log.isDebugEnabled()) {
                    log.debug("Client synchronized with server ({}), using REQUEST", clientTipSlot);
                }
                return PipelineDecision.REQUEST;
            }
            
            // Behind server's tip, start pipelining
            if (log.isDebugEnabled()) {
                log.debug("Client behind server ({} < {}), starting pipeline", clientTipSlot, serverTipSlot);
            }
            return PipelineDecision.PIPELINE;
        }
        
        long slotsBehind = serverTipSlot - clientTipSlot;
        
        // Always collect if we're approaching the server's tip to avoid deadlock
        if (outstandingRequests >= slotsBehind) {
            if (log.isDebugEnabled()) {
                log.debug("Approaching server tip (outstanding: {}, slots behind: {}), collecting", 
                         outstandingRequests, slotsBehind);
            }
            return PipelineDecision.COLLECT;
        }
        
        // Apply network condition adaptations
        boolean networkStressed = networkMetrics.networkInstability() || 
                                networkMetrics.memoryPressure() > 70 ||
                                networkMetrics.pipelineEfficiency() < 0.8;
        
        if (networkStressed) {
            // Force high mode behavior under network stress
            if (log.isDebugEnabled()) {
                log.debug("Network stress detected, forcing collection behavior");
            }
            return PipelineDecision.COLLECT;
        }
        
        // Mode-specific behavior
        if (inHighMode) {
            // High mode: collect until we reach low mark
            if (log.isDebugEnabled()) {
                log.debug("HIGH mode: collecting (outstanding: {}, target: <= {})", outstandingRequests, lowMark);
            }
            return PipelineDecision.COLLECT;
        } else {
            // Low mode: collect or pipeline adaptively
            if (log.isDebugEnabled()) {
                log.debug("LOW mode: adaptive behavior (outstanding: {}, target: <= {})", outstandingRequests, highMark);
            }
            return PipelineDecision.COLLECT_OR_PIPELINE;
        }
    }
    
    @Override
    public String getStrategyName() {
        return String.format("Watermark(%d/%d)%s", lowMark, highMark, inHighMode ? "-HIGH" : "-LOW");
    }
    
    @Override
    public int getMaxPipelineDepth() {
        return highMark;
    }
    
    @Override
    public void onRequestCompleted(long responseTimeMs) {
        // Could track response times to adjust watermarks dynamically
    }
    
    @Override
    public void onRequestFailed(Throwable cause) {
        if (log.isDebugEnabled()) {
            log.debug("Pipeline request failed in WatermarkPipelineStrategy: {}", cause.getMessage());
        }
        
        // On failures, prefer high mode for more conservative behavior
        if (!inHighMode) {
            if (log.isDebugEnabled()) {
                log.debug("Switching to HIGH mode due to request failure");
            }
            inHighMode = true;
        }
    }
    
    /**
     * Get current mode for monitoring/debugging
     */
    public boolean isInHighMode() {
        return inHighMode;
    }
    
    /**
     * Force mode switch for testing or exceptional conditions
     */
    public void forceMode(boolean highMode) {
        if (log.isDebugEnabled()) {
            log.debug("Forcing mode switch: HIGH={}", highMode);
        }
        this.inHighMode = highMode;
    }
}