package com.bloxbean.cardano.yaci.node.runtime.consensus;

import com.bloxbean.cardano.yaci.events.api.DomainEventListener;
import com.bloxbean.cardano.yaci.node.api.consensus.AppConsensus;
import com.bloxbean.cardano.yaci.node.api.consensus.ConsensusProof;
import com.bloxbean.cardano.yaci.node.api.events.AppBlockConsensusEvent;
import lombok.extern.slf4j.Slf4j;

/**
 * Default listener for AppBlockConsensusEvent.
 * Delegates to the configured AppConsensus implementation.
 */
@Slf4j
public class DefaultAppConsensusListener {

    private final AppConsensus consensus;

    public DefaultAppConsensusListener(AppConsensus consensus) {
        this.consensus = consensus;
    }

    @DomainEventListener(order = 100)
    public void onAppBlockConsensus(AppBlockConsensusEvent event) {
        if (event.isRejected()) {
            log.debug("App block consensus already rejected, skipping");
            return;
        }

        ConsensusProof proof = event.block().getConsensusProof();
        if (proof == null) {
            event.reject("app-consensus", "No consensus proof provided");
            return;
        }

        boolean valid = consensus.verifyProof(event.block(), proof);
        if (!valid) {
            event.reject("app-consensus",
                    String.format("Consensus verification failed for block %d (mode=%s, sigs=%d, threshold=%d)",
                            event.block().getBlockNumber(),
                            consensus.consensusMode(),
                            proof.signatureCount(),
                            proof.getThreshold()));
        }
    }
}
