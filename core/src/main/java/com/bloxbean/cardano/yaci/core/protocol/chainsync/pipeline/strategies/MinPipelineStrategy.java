package com.bloxbean.cardano.yaci.core.protocol.chainsync.pipeline.strategies;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.pipeline.NetworkMetrics;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.pipeline.PipelineDecision;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.pipeline.PipelineDecisionStrategy;
import lombok.extern.slf4j.Slf4j;

/**
 * Minimum pipelining strategy that eagerly collects responses.
 * 
 * This strategy is based on the Haskell implementation's `pipelineDecisionMin`
 * and provides conservative pipelining behavior with eager collection. It will:
 * 
 * - Pipeline requests when behind the server's tip
 * - Switch to non-pipelined mode when synchronized with server
 * - Use COLLECT_OR_PIPELINE for adaptive response collection
 * - Collect eagerly when pipeline limits are approached
 * 
 * Best used when network conditions are unstable, latency is low,
 * or when memory usage needs to be minimized, as it keeps fewer
 * requests in flight.
 */
@Slf4j
public class MinPipelineStrategy implements PipelineDecisionStrategy {
    
    private final int maxPipelineDepth;
    
    public MinPipelineStrategy(int maxPipelineDepth) {
        this.maxPipelineDepth = Math.max(1, maxPipelineDepth);
    }
    
    public MinPipelineStrategy() {
        this(25); // More conservative default than MaxPipelineStrategy
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
        if (outstandingRequests >= slotsBehind) {
            if (log.isDebugEnabled()) {
                log.debug("Approaching server tip (outstanding: {}, slots behind: {}), collecting", 
                         outstandingRequests, slotsBehind);
            }
            return PipelineDecision.COLLECT;
        }
        
        // Consider network conditions - be more conservative under stress
        if (networkMetrics.networkInstability() || networkMetrics.memoryPressure() > 50) {
            if (log.isDebugEnabled()) {
                log.debug("Network stress detected, using eager collection (instability: {}, memory: {}%)", 
                         networkMetrics.networkInstability(), networkMetrics.memoryPressure());
            }
            return PipelineDecision.COLLECT_OR_PIPELINE;
        }
        
        // MinPipeline strategy prefers eager collection over aggressive pipelining
        // Use COLLECT_OR_PIPELINE instead of PIPELINE for most cases
        if (log.isDebugEnabled()) {
            log.debug("Using adaptive collection ({}/{}, {} slots behind)", 
                     outstandingRequests, maxPipelineDepth, slotsBehind);
        }
        return PipelineDecision.COLLECT_OR_PIPELINE;
    }
    
    @Override
    public String getStrategyName() {
        return "MinPipeline(" + maxPipelineDepth + ")";
    }
    
    @Override
    public int getMaxPipelineDepth() {
        return maxPipelineDepth;
    }
    
    @Override
    public void onRequestFailed(Throwable cause) {
        if (log.isDebugEnabled()) {
            log.debug("Pipeline request failed in MinPipelineStrategy: {}", cause.getMessage());
        }
    }
}