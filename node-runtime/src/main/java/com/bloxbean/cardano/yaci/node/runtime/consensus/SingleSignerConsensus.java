package com.bloxbean.cardano.yaci.node.runtime.consensus;

import com.bloxbean.cardano.yaci.node.api.consensus.*;
import com.bloxbean.cardano.yaci.node.api.ledger.AppBlock;
import lombok.extern.slf4j.Slf4j;

import java.security.*;

/**
 * SingleSigner consensus: the proposer node is the sole authority.
 * Always accepts all proposed blocks. Signs with a local Ed25519 key.
 */
@Slf4j
public class SingleSignerConsensus implements AppConsensus {

    private final KeyPair keyPair;
    private final ConsensusParams params;

    public SingleSignerConsensus() {
        this(ConsensusParams.builder().build());
    }

    public SingleSignerConsensus(ConsensusParams params) {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
            this.keyPair = kpg.generateKeyPair();
            this.params = params;
            log.info("SingleSignerConsensus initialized");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Ed25519 not available", e);
        }
    }

    @Override
    public boolean canPropose() {
        return true;
    }

    @Override
    public ConsensusProof createProof(AppBlock block) {
        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initSign(keyPair.getPrivate());
            sig.update(block.getBlockHash());
            byte[] signature = sig.sign();
            byte[] publicKey = keyPair.getPublic().getEncoded();
            return ConsensusProof.singleSigner(publicKey, signature);
        } catch (Exception e) {
            log.error("Failed to create consensus proof", e);
            return ConsensusProof.singleSigner(new byte[0], new byte[0]);
        }
    }

    @Override
    public boolean verifyProof(AppBlock block, ConsensusProof proof) {
        // SingleSigner: always accepts
        return proof != null && proof.signatureCount() > 0;
    }

    @Override
    public ConsensusMode consensusMode() {
        return ConsensusMode.SINGLE_SIGNER;
    }

    @Override
    public ConsensusParams params() {
        return params;
    }
}
