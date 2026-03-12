package com.bloxbean.cardano.yaci.node.api.consensus;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * Proof of consensus for an app block. Contains the signatures or votes
 * collected during the consensus round.
 */
@Getter
@Builder
public class ConsensusProof {
    private final ConsensusMode mode;
    private final List<byte[]> signatures;
    private final List<byte[]> signerKeys;
    private final int threshold;
    private final byte[] proposerKey;

    /**
     * Create a trivial proof for SingleSigner mode.
     */
    public static ConsensusProof singleSigner(byte[] proposerKey, byte[] signature) {
        return ConsensusProof.builder()
                .mode(ConsensusMode.SINGLE_SIGNER)
                .proposerKey(proposerKey)
                .signatures(List.of(signature))
                .signerKeys(List.of(proposerKey))
                .threshold(1)
                .build();
    }

    public int signatureCount() {
        return signatures != null ? signatures.size() : 0;
    }

    public boolean meetsThreshold() {
        return signatureCount() >= threshold;
    }
}
