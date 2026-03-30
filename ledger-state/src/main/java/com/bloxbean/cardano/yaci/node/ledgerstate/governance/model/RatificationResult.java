package com.bloxbean.cardano.yaci.node.ledgerstate.governance.model;

import com.bloxbean.cardano.yaci.core.model.governance.GovActionId;

/**
 * Result of evaluating a governance proposal during ratification.
 */
public record RatificationResult(
        GovActionId govActionId,
        GovActionRecord proposal,
        Status status
) {
    public enum Status {
        /** All applicable thresholds met — ratified, will be enacted next epoch */
        RATIFIED,
        /** Past its lifetime — expired, deposit will be refunded */
        EXPIRED,
        /** Still within voting window, not yet ratified */
        ACTIVE
    }

    public boolean isRatified() {
        return status == Status.RATIFIED;
    }

    public boolean isExpired() {
        return status == Status.EXPIRED;
    }
}
