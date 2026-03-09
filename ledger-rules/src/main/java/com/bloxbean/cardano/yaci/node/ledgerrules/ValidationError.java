package com.bloxbean.cardano.yaci.node.ledgerrules;

/**
 * Represents a single validation error from Cardano ledger rule evaluation.
 *
 * @param rule    the validation rule that failed (e.g. "UtxoNotFound", "CborDeserialization")
 * @param message human-readable error description
 * @param phase   which validation phase detected the error
 */
public record ValidationError(String rule, String message, Phase phase) {

    public enum Phase {
        PHASE_1,
        PHASE_2
    }
}
