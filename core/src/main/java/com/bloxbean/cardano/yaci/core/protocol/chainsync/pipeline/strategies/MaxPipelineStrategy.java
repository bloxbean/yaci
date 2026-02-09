package com.bloxbean.cardano.yaci.core.protocol.chainsync.pipeline.strategies;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.pipeline.NetworkMetrics;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.pipeline.PipelineDecision;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.pipeline.PipelineDecisionStrategy;
import lombok.extern.slf4j.Slf4j;

/**
 * Maximum pipelining strategy that aggressively pipelines requests up to a limit.
 * 
 * This strategy is based on the Haskell implementation's `pipelineDecisionMax`
 * and provides the most aggressive pipelining behavior. It will:
 * 
 * - Pipeline requests when behind the server's tip
 * - Switch to non-pipelined mode when synchronized with server
 * - Collect responses when pipeline limit is reached
 * - Collect responses when approaching server's tip to avoid deadlock
 * 
 * Best used when network conditions are stable and latency is high,
 * as it maximizes throughput by keeping the pipeline full.
 */
@Slf4j
public class MaxPipelineStrategy implements PipelineDecisionStrategy {
    
    private final int maxPipelineDepth;
    
    public MaxPipelineStrategy(int maxPipelineDepth) {
        this.maxPipelineDepth = Math.max(1, maxPipelineDepth);
    }
    
    public MaxPipelineStrategy() {
        this(50); // Default from Yaci's current batchSize
    }
    
    @Override
    public PipelineDecision decide(int outstandingRequests, 
                                  long clientTipSlot, 
                                  long serverTipSlot, 
                                  NetworkMetrics networkMetrics) {
        
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
        
        // We have outstanding requests
        long slotsBehind = serverTipSlot - clientTipSlot;
        
        // Collect if we've hit the pipeline limit
        if (outstandingRequests >= maxPipelineDepth) {
            if (log.isDebugEnabled()) {
                log.debug("Pipeline limit reached ({}/{}), collecting", outstandingRequests, maxPipelineDepth);
            }
            return PipelineDecision.COLLECT;
        }
        
        // Collect if we're approaching the server's tip to avoid deadlock
        // Add safety margin based on outstanding requests to prevent getting stuck
        if (outstandingRequests >= slotsBehind) {
            if (log.isDebugEnabled()) {
                log.debug("Approaching server tip (outstanding: {}, slots behind: {}), collecting", 
                         outstandingRequests, slotsBehind);
            }
            return PipelineDecision.COLLECT;
        }
        
        // Consider network conditions for adaptive behavior
        if (networkMetrics.networkInstability() && outstandingRequests > maxPipelineDepth / 2) {
            if (log.isDebugEnabled()) {
                log.debug("Network instability detected, reducing pipeline aggression");
            }
            return PipelineDecision.COLLECT;
        }
        
        // Otherwise, continue pipelining
        if (log.isDebugEnabled()) {
            log.debug("Continuing pipeline ({}/{}, {} slots behind)", 
                     outstandingRequests, maxPipelineDepth, slotsBehind);
        }
        return PipelineDecision.PIPELINE;
    }
    
    @Override
    public String getStrategyName() {
        return "MaxPipeline(" + maxPipelineDepth + ")";
    }
    
    @Override
    public int getMaxPipelineDepth() {
        return maxPipelineDepth;
    }
    
    @Override
    public void onRequestFailed(Throwable cause) {
        if (log.isDebugEnabled()) {
            log.debug("Pipeline request failed in MaxPipelineStrategy: {}", cause.getMessage());
        }
    }
}