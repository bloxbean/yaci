package com.bloxbean.cardano.yaci.core.protocol.chainsync.pipeline;

import lombok.Builder;
import lombok.Data;

/**
 * Comprehensive configuration for ChainSync pipeline behavior.
 * 
 * This class provides a centralized configuration system for all pipeline
 * features including strategy selection, error recovery, metrics, and
 * performance tuning parameters.
 */
@Data
@Builder(toBuilder = true)
public class PipelineConfiguration {
    
    /**
     * Pipeline strategy to use for decision making
     */
    @Builder.Default
    private PipelineDecisionStrategy strategy = PipelineStrategies.adaptive();
    
    /**
     * Error recovery configuration
     */
    @Builder.Default
    private PipelineErrorRecovery.ErrorRecoveryConfig errorRecoveryConfig = 
        PipelineErrorRecovery.ErrorRecoveryConfig.defaultConfig();
    
    /**
     * Whether to enable enhanced pipelining (vs legacy batch mode)
     */
    @Builder.Default
    private boolean enhancedPipeliningEnabled = true;
    
    /**
     * Whether to enable detailed metrics collection
     */
    @Builder.Default
    private boolean metricsEnabled = true;
    
    /**
     * Interval for automatic statistics logging (seconds, 0 = disabled)
     */
    @Builder.Default
    private int statsLoggingInterval = 300; // 5 minutes
    
    /**
     * Maximum memory pressure threshold (0-100%)
     */
    @Builder.Default
    private int maxMemoryPressure = 80;
    
    /**
     * Network timeout for pipeline requests (milliseconds)
     */
    @Builder.Default
    private long networkTimeoutMs = 30000; // 30 seconds
    
    /**
     * Whether to automatically adjust strategy based on network conditions
     */
    @Builder.Default
    private boolean adaptiveStrategyEnabled = true;
    
    /**
     * Minimum efficiency threshold before switching to conservative strategy
     */
    @Builder.Default
    private double minEfficiencyThreshold = 0.7;
    
    // Pre-configured setups for common scenarios
    
    /**
     * Configuration optimized for initial blockchain sync from genesis.
     * Uses aggressive pipelining for maximum throughput.
     */
    public static PipelineConfiguration syncFromGenesis() {
        return PipelineConfiguration.builder()
                .strategy(PipelineStrategies.syncFromGenesis())
                .errorRecoveryConfig(PipelineErrorRecovery.ErrorRecoveryConfig.aggressiveConfig())
                .enhancedPipeliningEnabled(true)
                .metricsEnabled(true)
                .statsLoggingInterval(60) // More frequent logging during sync
                .maxMemoryPressure(90) // Allow higher memory usage
                .networkTimeoutMs(60000) // Longer timeout for sync
                .adaptiveStrategyEnabled(true)
                .minEfficiencyThreshold(0.6) // Lower threshold for sync
                .build();
    }
    
    /**
     * Configuration optimized for following the tip of the chain.
     * Uses conservative pipelining for low latency.
     */
    public static PipelineConfiguration followTip() {
        return PipelineConfiguration.builder()
                .strategy(PipelineStrategies.followTip())
                .errorRecoveryConfig(PipelineErrorRecovery.ErrorRecoveryConfig.defaultConfig())
                .enhancedPipeliningEnabled(true)
                .metricsEnabled(true)
                .statsLoggingInterval(600) // Less frequent logging at tip
                .maxMemoryPressure(70) // Conservative memory usage
                .networkTimeoutMs(15000) // Shorter timeout for responsiveness
                .adaptiveStrategyEnabled(true)
                .minEfficiencyThreshold(0.8) // Higher threshold at tip
                .build();
    }
    
    /**
     * Configuration for development and debugging.
     * Uses sequential processing with detailed logging.
     */
    public static PipelineConfiguration development() {
        return PipelineConfiguration.builder()
                .strategy(PipelineStrategies.sequential())
                .errorRecoveryConfig(PipelineErrorRecovery.ErrorRecoveryConfig.conservativeConfig())
                .enhancedPipeliningEnabled(true) // Enable for metrics even if sequential
                .metricsEnabled(true)
                .statsLoggingInterval(30) // Frequent logging for debugging
                .maxMemoryPressure(60)
                .networkTimeoutMs(10000) // Shorter timeout for debugging
                .adaptiveStrategyEnabled(false) // Disable adaptation for predictability
                .minEfficiencyThreshold(0.5)
                .build();
    }
    
    /**
     * Configuration for production environments with stability focus.
     * Uses adaptive strategy with conservative error recovery.
     */
    public static PipelineConfiguration production() {
        return PipelineConfiguration.builder()
                .strategy(PipelineStrategies.adaptive(15, 40)) // Conservative watermarks
                .errorRecoveryConfig(PipelineErrorRecovery.ErrorRecoveryConfig.conservativeConfig())
                .enhancedPipeliningEnabled(true)
                .metricsEnabled(true)
                .statsLoggingInterval(900) // 15 minutes
                .maxMemoryPressure(75)
                .networkTimeoutMs(45000) // Longer timeout for stability
                .adaptiveStrategyEnabled(true)
                .minEfficiencyThreshold(0.75)
                .build();
    }
    
    /**
     * Configuration for resource-constrained environments.
     * Minimizes memory usage and processing overhead.
     */
    public static PipelineConfiguration resourceConstrained() {
        return PipelineConfiguration.builder()
                .strategy(PipelineStrategies.conservative(10)) // Low pipeline depth
                .errorRecoveryConfig(PipelineErrorRecovery.ErrorRecoveryConfig.conservativeConfig())
                .enhancedPipeliningEnabled(true)
                .metricsEnabled(false) // Disable metrics to save memory
                .statsLoggingInterval(0) // Disable stats logging
                .maxMemoryPressure(50)
                .networkTimeoutMs(20000)
                .adaptiveStrategyEnabled(false) // Reduce CPU overhead
                .minEfficiencyThreshold(0.6)
                .build();
    }
    
    /**
     * Configuration for high-performance scenarios.
     * Maximizes throughput with aggressive settings.
     */
    public static PipelineConfiguration highPerformance() {
        return PipelineConfiguration.builder()
                .strategy(PipelineStrategies.aggressive(100)) // High pipeline depth
                .errorRecoveryConfig(PipelineErrorRecovery.ErrorRecoveryConfig.aggressiveConfig())
                .enhancedPipeliningEnabled(true)
                .metricsEnabled(true)
                .statsLoggingInterval(120) // Moderate logging frequency
                .maxMemoryPressure(95) // Allow very high memory usage
                .networkTimeoutMs(120000) // Very long timeout
                .adaptiveStrategyEnabled(true)
                .minEfficiencyThreshold(0.6) // Lower threshold for max throughput
                .build();
    }
    
    /**
     * Get configuration name for logging/debugging purposes.
     */
    public String getConfigurationName() {
        if (this == syncFromGenesis()) return "SyncFromGenesis";
        if (this == followTip()) return "FollowTip";
        if (this == development()) return "Development";
        if (this == production()) return "Production";
        if (this == resourceConstrained()) return "ResourceConstrained";
        if (this == highPerformance()) return "HighPerformance";
        return "Custom(" + strategy.getStrategyName() + ")";
    }
    
    /**
     * Validate configuration parameters.
     */
    public void validate() {
        if (strategy == null) {
            throw new IllegalArgumentException("Pipeline strategy cannot be null");
        }
        if (errorRecoveryConfig == null) {
            throw new IllegalArgumentException("Error recovery config cannot be null");
        }
        if (maxMemoryPressure < 0 || maxMemoryPressure > 100) {
            throw new IllegalArgumentException("Memory pressure must be between 0 and 100");
        }
        if (networkTimeoutMs <= 0) {
            throw new IllegalArgumentException("Network timeout must be positive");
        }
        if (statsLoggingInterval < 0) {
            throw new IllegalArgumentException("Stats logging interval must be non-negative");
        }
        if (minEfficiencyThreshold < 0.0 || minEfficiencyThreshold > 1.0) {
            throw new IllegalArgumentException("Efficiency threshold must be between 0.0 and 1.0");
        }
    }
    
    /**
     * Create a copy of this configuration with modified strategy.
     */
    public PipelineConfiguration withStrategy(PipelineDecisionStrategy newStrategy) {
        return this.toBuilder()
                .strategy(newStrategy)
                .build();
    }
    
    /**
     * Create a copy of this configuration with enhanced pipelining disabled.
     */
    public PipelineConfiguration withoutEnhancedPipelining() {
        return this.toBuilder()
                .enhancedPipeliningEnabled(false)
                .build();
    }
}