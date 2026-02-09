package com.bloxbean.cardano.yaci.core.protocol.chainsync.pipeline.strategies;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.pipeline.NetworkMetrics;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.pipeline.PipelineDecision;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.pipeline.PipelineDecisionStrategy;
import lombok.extern.slf4j.Slf4j;

/**
 * Sequential (non-pipelined) strategy that sends one request at a time.
 * 
 * This strategy disables pipelining entirely and always uses REQUEST
 * or COLLECT decisions. It provides:
 * 
 * - Maximum compatibility with existing code
 * - Lowest memory usage
 * - Simplest debugging and error handling
 * - Predictable request-response ordering
 * 
 * Best used for:
 * - Legacy applications that expect sequential behavior
 * - Debugging pipeline issues
 * - Memory-constrained environments
 * - Applications that process blocks synchronously
 */
@Slf4j
public class SequentialStrategy implements PipelineDecisionStrategy {
    
    @Override
    public PipelineDecision decide(int outstandingRequests, 
                                  long clientTipSlot, 
                                  long serverTipSlot, 
                                  NetworkMetrics networkMetrics) {
        
        // If we have outstanding requests, collect them first
        if (outstandingRequests > 0) {
            if (log.isDebugEnabled()) {
                log.debug("Sequential mode: collecting outstanding request");
            }
            return PipelineDecision.COLLECT;
        }
        
        // Otherwise, send a non-pipelined request
        if (log.isDebugEnabled()) {
            log.debug("Sequential mode: sending non-pipelined request");
        }
        return PipelineDecision.REQUEST;
    }
    
    @Override
    public String getStrategyName() {
        return "Sequential";
    }
    
    @Override
    public int getMaxPipelineDepth() {
        return 1; // Effectively no pipelining
    }
}