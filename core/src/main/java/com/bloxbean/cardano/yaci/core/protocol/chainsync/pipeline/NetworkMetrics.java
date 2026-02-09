package com.bloxbean.cardano.yaci.core.protocol.chainsync.pipeline;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * Network and system metrics used by pipeline decision strategies
 * to make informed decisions about pipeline behavior.
 */
@Data
@Builder
@Accessors(fluent = true)
public class NetworkMetrics {
    /**
     * Average response time in milliseconds for recent requests
     */
    @Builder.Default
    private long avgResponseTimeMs = 100;
    
    /**
     * Current memory pressure as a percentage (0-100)
     */
    @Builder.Default  
    private int memoryPressure = 0;
    
    /**
     * Number of connection failures in recent history
     */
    @Builder.Default
    private int recentConnectionFailures = 0;
    
    /**
     * Whether we're currently experiencing network issues
     */
    @Builder.Default
    private boolean networkInstability = false;
    
    /**
     * Current bandwidth utilization as a percentage (0-100)
     */
    @Builder.Default
    private int bandwidthUtilization = 50;
    
    /**
     * Pipeline efficiency ratio (successful/total pipeline requests)
     */
    @Builder.Default
    private double pipelineEfficiency = 1.0;
    
    /**
     * Create default metrics for normal network conditions
     */
    public static NetworkMetrics defaults() {
        return NetworkMetrics.builder().build();
    }
    
    /**
     * Create metrics indicating degraded network conditions
     */
    public static NetworkMetrics degraded() {
        return NetworkMetrics.builder()
                .avgResponseTimeMs(500)
                .memoryPressure(75)
                .recentConnectionFailures(3)
                .networkInstability(true)
                .bandwidthUtilization(90)
                .pipelineEfficiency(0.7)
                .build();
    }
}