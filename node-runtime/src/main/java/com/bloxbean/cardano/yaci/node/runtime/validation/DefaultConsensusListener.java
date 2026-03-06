package com.bloxbean.cardano.yaci.node.runtime.validation;

import com.bloxbean.cardano.yaci.events.api.DomainEventListener;
import com.bloxbean.cardano.yaci.node.api.events.BlockConsensusEvent;
import lombok.extern.slf4j.Slf4j;

/**
 * Default consensus listener that accepts all blocks.
 * Placeholder for future Ouroboros consensus implementation.
 * <p>
 * Runs at {@code order = 100}. Plugins can add real consensus logic
 * by registering listeners at any order.
 */
@Slf4j
public class DefaultConsensusListener {

    @DomainEventListener(order = 100)
    public void onBlockConsensus(BlockConsensusEvent event) {
        // Accept all blocks by default — no-op.
        // Future: implement slot leader verification, VRF checks, etc.
        log.trace("Block {} at slot {} accepted by default consensus",
                event.blockNumber(), event.slot());
    }
}
