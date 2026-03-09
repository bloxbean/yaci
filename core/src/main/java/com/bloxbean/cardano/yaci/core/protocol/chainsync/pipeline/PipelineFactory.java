package com.bloxbean.cardano.yaci.core.protocol.chainsync.pipeline;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.n2n.ChainsyncAgent;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Factory for creating and configuring ChainSync agents with enhanced pipeline capabilities.
 * 
 * This factory simplifies the setup of ChainSync agents with various pipeline configurations
 * and provides helper methods for common scenarios like sync-from-genesis, tip-following,
 * and development/testing setups.
 */
@Slf4j
public class PipelineFactory {
    
    private static final ScheduledExecutorService statsExecutor = 
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pipeline-stats");
            t.setDaemon(true);
            return t;
        });
    
    /**
     * Create a ChainSync agent with default pipeline configuration.
     */
    public static ChainsyncAgent createDefault(Point[] knownPoints) {
        return create(knownPoints, PipelineConfiguration.production());
    }
    
    /**
     * Create a ChainSync agent optimized for syncing from genesis.
     */
    public static ChainsyncAgent createForSyncFromGenesis(Point[] knownPoints) {
        return create(knownPoints, PipelineConfiguration.syncFromGenesis());
    }
    
    /**
     * Create a ChainSync agent optimized for following the tip.
     */
    public static ChainsyncAgent createForTipFollowing(Point[] knownPoints) {
        return create(knownPoints, PipelineConfiguration.followTip());
    }
    
    /**
     * Create a ChainSync agent for development/debugging.
     */
    public static ChainsyncAgent createForDevelopment(Point[] knownPoints) {
        return create(knownPoints, PipelineConfiguration.development());
    }
    
    /**
     * Create a ChainSync agent for production environments.
     */
    public static ChainsyncAgent createForProduction(Point[] knownPoints) {
        return create(knownPoints, PipelineConfiguration.production());
    }
    
    /**
     * Create a ChainSync agent for resource-constrained environments.
     */
    public static ChainsyncAgent createResourceConstrained(Point[] knownPoints) {
        return create(knownPoints, PipelineConfiguration.resourceConstrained());
    }
    
    /**
     * Create a ChainSync agent for high-performance scenarios.
     */
    public static ChainsyncAgent createHighPerformance(Point[] knownPoints) {
        return create(knownPoints, PipelineConfiguration.highPerformance());
    }
    
    /**
     * Create a ChainSync agent with custom pipeline configuration.
     */
    public static ChainsyncAgent create(Point[] knownPoints, PipelineConfiguration config) {
        config.validate();
        
        ChainsyncAgent agent = new ChainsyncAgent(knownPoints);
        configurePipeline(agent, config);
        
        if (log.isInfoEnabled()) {
            log.info("🎆 ChainSync agent created with {} configuration", config.getConfigurationName());
        }
        
        return agent;
    }
    
    /**
     * Create a ChainSync agent with time-bounded sync.
     */
    public static ChainsyncAgent create(Point[] knownPoints, long stopSlotNo, int agentNo, 
                                       PipelineConfiguration config) {
        config.validate();
        
        ChainsyncAgent agent = new ChainsyncAgent(knownPoints, stopSlotNo, agentNo);
        configurePipeline(agent, config);
        
        if (log.isInfoEnabled()) {
            log.info("🎆 Time-bounded ChainSync agent created: stop at slot {} with {} configuration",
                     stopSlotNo, config.getConfigurationName());
        }
        
        return agent;
    }
    
    /**
     * Configure an existing ChainSync agent with pipeline settings.
     */
    public static void configurePipeline(ChainsyncAgent agent, PipelineConfiguration config) {
        // Set pipeline strategy
        agent.setPipelineStrategy(config.getStrategy());
        
        // Enable enhanced pipelining if requested
        agent.enableEnhancedPipelining(config.isEnhancedPipeliningEnabled());
        
        // Setup automatic statistics logging if enabled
        if (config.isMetricsEnabled() && config.getStatsLoggingInterval() > 0) {
            scheduleStatsLogging(agent, config.getStatsLoggingInterval());
        }
        
        if (log.isDebugEnabled()) {
            log.debug("🔧 Pipeline configured: strategy={}, enhanced={}, metrics={}, logging={}s",
                     config.getStrategy().getStrategyName(),
                     config.isEnhancedPipeliningEnabled(),
                     config.isMetricsEnabled(),
                     config.getStatsLoggingInterval());
        }
    }
    
    /**
     * Update an existing agent's pipeline strategy.
     */
    public static void updateStrategy(ChainsyncAgent agent, PipelineDecisionStrategy newStrategy) {
        agent.setPipelineStrategy(newStrategy);
        if (log.isInfoEnabled()) {
            log.info("🔄 Pipeline strategy updated to: {}", newStrategy.getStrategyName());
        }
    }
    
    /**
     * Enable or disable enhanced pipelining on an existing agent.
     */
    public static void setEnhancedPipelining(ChainsyncAgent agent, boolean enabled) {
        agent.enableEnhancedPipelining(enabled);
        if (log.isInfoEnabled()) {
            log.info("🔧 Enhanced pipelining {}", enabled ? "enabled" : "disabled");
        }
    }
    
    /**
     * Get current pipeline status for an agent.
     */
    public static PipelineStatus getStatus(ChainsyncAgent agent) {
        PipelineMetrics metrics = agent.getPipelineMetrics();
        
        return PipelineStatus.builder()
                .enhanced(agent.isEnhancedPipeliningEnabled())
                .strategyName(agent.getPipelineStrategyName())
                .outstandingRequests(agent.getPipelineOutstandingRequests())
                .totalRequests(metrics != null ? metrics.getTotalRequests().sum() : 0)
                .successfulRequests(metrics != null ? metrics.getSuccessfulRequests().sum() : 0)
                .efficiency(metrics != null ? metrics.getPipelineEfficiency() : 0.0)
                .averageResponseTime(metrics != null ? metrics.getAverageResponseTime() : 0.0)
                .blocksPerSecond(metrics != null ? metrics.getBlocksPerSecond() : 0.0)
                .build();
    }
    
    /**
     * Schedule automatic statistics logging for an agent.
     */
    private static void scheduleStatsLogging(ChainsyncAgent agent, int intervalSeconds) {
        statsExecutor.scheduleWithFixedDelay(
            () -> {
                try {
                    agent.logPipelineStatistics();
                } catch (Exception e) {
                    log.warn("Error logging pipeline statistics: {}", e.getMessage());
                }
            },
            intervalSeconds, intervalSeconds, TimeUnit.SECONDS
        );
        
        if (log.isDebugEnabled()) {
            log.debug("⏰ Stats logging scheduled every {} seconds", intervalSeconds);
        }
    }
    
    /**
     * Shutdown the factory and cleanup resources.
     */
    public static void shutdown() {
        statsExecutor.shutdown();
        try {
            if (!statsExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                statsExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            statsExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        if (log.isDebugEnabled()) {
            log.debug("💯 Pipeline factory shutdown completed");
        }
    }
    
    /**
     * Represents the current status of a pipeline.
     */
    @lombok.Data
    @lombok.Builder
    public static class PipelineStatus {
        private final boolean enhanced;
        private final String strategyName;
        private final int outstandingRequests;
        private final long totalRequests;
        private final long successfulRequests;
        private final double efficiency;
        private final double averageResponseTime;
        private final double blocksPerSecond;
        
        public String getSummary() {
            return String.format(
                "Pipeline Status: %s strategy, %s, outstanding=%d, total=%d, success=%.1f%%, " +
                "avg_response=%.1fms, throughput=%.1f blocks/s",
                strategyName,
                enhanced ? "enhanced" : "legacy",
                outstandingRequests,
                totalRequests,
                efficiency * 100,
                averageResponseTime,
                blocksPerSecond
            );
        }
        
        public boolean isHealthy() {
            return efficiency >= 0.7 && averageResponseTime < 5000; // 5 second threshold
        }
        
        public boolean isHighThroughput() {
            return blocksPerSecond >= 10 && efficiency >= 0.8;
        }
    }
}