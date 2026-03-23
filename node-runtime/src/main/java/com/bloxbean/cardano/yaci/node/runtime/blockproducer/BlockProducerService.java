package com.bloxbean.cardano.yaci.node.runtime.blockproducer;

/**
 * Common interface for block producer implementations.
 * <p>
 * {@link DevnetBlockProducer} produces blocks unconditionally at a fixed interval (for devnets).
 * {@link SlotLeaderBlockProducer} checks each slot for Praos leader eligibility (for public networks).
 */
public interface BlockProducerService {
    void start();
    void stop();
    boolean isRunning();
    void resetToChainTip();
}
