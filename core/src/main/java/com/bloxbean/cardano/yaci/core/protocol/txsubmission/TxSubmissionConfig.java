package com.bloxbean.cardano.yaci.core.protocol.txsubmission;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * Configuration for TxSubmission server protocol behavior
 *
 * Simple configuration for the blocking-only TxSubmission implementation.
 */
@Data
@Builder
@Slf4j
public class TxSubmissionConfig {

    /**
     * Maximum number of transactions to request per batch
     * Must be ≤ 10 to respect protocol constraint
     * Default: 10 transactions (safe since we process all before requesting more)
     */
    @Builder.Default
    private final int batchSize = 10;

    /**
     * Always use blocking mode in this implementation
     * Default: true (blocking-only)
     */
    @Builder.Default
    private final boolean useBlockingMode = true;

    /**
     * Create a default configuration for TxSubmission
     */
    public static TxSubmissionConfig createDefault() {
        return TxSubmissionConfig.builder().build();
    }

    /**
     * Create a configuration with custom batch size
     */
    public static TxSubmissionConfig withBatchSize(int batchSize) {
        return TxSubmissionConfig.builder()
                .batchSize(batchSize)
                .build();
    }

    /**
     * Create a configuration for testing
     */
    public static TxSubmissionConfig createTestConfig() {
        return TxSubmissionConfig.builder()
                .batchSize(3)  // Smaller batches for testing
                .build();
    }

    /**
     * Create a configuration with periodic requests disabled (compatibility)
     * Note: Periodic requests are not used in blocking-only mode
     */
    public static TxSubmissionConfig createDisabled() {
        return createDefault();
    }

    /**
     * Validate the configuration and log warnings for potential issues
     */
    public void validate() {
        if (batchSize <= 0) {
            log.warn("TxSubmissionConfig: batchSize must be positive, got: {}", batchSize);
        }

        if (batchSize > 10) {
            log.warn("TxSubmissionConfig: batchSize ({}) exceeds protocol limit of 10", batchSize);
        }

        if (!useBlockingMode) {
            log.warn("TxSubmissionConfig: non-blocking mode not supported in this implementation");
        }

        log.debug("TxSubmissionConfig validated: {}", this);
    }


    @Override
    public String toString() {
        return String.format("TxSubmissionConfig{batchSize=%d, blockingMode=%s}",
                batchSize, useBlockingMode);
    }

    // Compatibility methods for existing code
    public boolean isPeriodicRequestsEnabled() {
        return false; // No periodic requests in blocking-only mode
    }
}
