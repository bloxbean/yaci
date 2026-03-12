package com.bloxbean.cardano.yaci.node.api.consensus;

import lombok.Builder;
import lombok.Getter;

/**
 * Parameters for consensus operation.
 */
@Getter
@Builder
public class ConsensusParams {
    /** Required number of signatures/votes to finalize (for multisig: n in n-of-m) */
    @Builder.Default
    private int threshold = 1;

    /** Total number of signers/validators in the set */
    @Builder.Default
    private int totalSigners = 1;

    /** Timeout in milliseconds for consensus round before declaring failure */
    @Builder.Default
    private long timeoutMs = 30000;

    /** App block production interval in milliseconds */
    @Builder.Default
    private int blockIntervalMs = 5000;
}
