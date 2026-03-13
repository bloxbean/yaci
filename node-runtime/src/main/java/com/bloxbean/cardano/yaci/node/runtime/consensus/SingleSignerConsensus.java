package com.bloxbean.cardano.yaci.node.runtime.consensus;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.Keys;
import com.bloxbean.cardano.client.crypto.api.SigningProvider;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.yaci.node.api.consensus.*;
import com.bloxbean.cardano.yaci.node.api.ledger.AppBlock;
import lombok.extern.slf4j.Slf4j;

/**
 * SingleSigner consensus: the proposer node is the sole authority.
 * Always accepts all proposed blocks. Signs with a local Ed25519 key.
 */
@Slf4j
public class SingleSignerConsensus implements AppConsensus {

    private final byte[] privateKey; // 32-byte raw Ed25519 seed
    private final byte[] publicKey;  // 32-byte raw Ed25519 public key
    private final ConsensusParams params;
    private final SigningProvider signingProvider;

    public SingleSignerConsensus() {
        this(ConsensusParams.builder().build());
    }

    public SingleSignerConsensus(ConsensusParams params) {
        try {
            Keys keys = KeyGenUtil.generateKey();
            this.privateKey = keys.getSkey().getBytes();
            this.publicKey = keys.getVkey().getBytes();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate Ed25519 key pair", e);
        }
        this.params = params;
        this.signingProvider = CryptoConfiguration.INSTANCE.getSigningProvider();
        log.info("SingleSignerConsensus initialized");
    }

    /**
     * Create with a pre-existing key pair (e.g., from config).
     */
    public SingleSignerConsensus(byte[] privateKey, byte[] publicKey, ConsensusParams params) {
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.params = params;
        this.signingProvider = CryptoConfiguration.INSTANCE.getSigningProvider();
        log.info("SingleSignerConsensus initialized with provided key");
    }

    @Override
    public boolean canPropose() {
        return true;
    }

    @Override
    public ConsensusProof createProof(AppBlock block) {
        byte[] signature = signingProvider.sign(block.getBlockHash(), privateKey);
        return ConsensusProof.singleSigner(publicKey, signature);
    }

    @Override
    public boolean verifyProof(AppBlock block, ConsensusProof proof) {
        // SingleSigner: always accepts as long as there's a signature
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

    @Override
    public byte[] getLocalPublicKey() {
        return publicKey;
    }

    @Override
    public byte[] sign(byte[] data) {
        return signingProvider.sign(data, privateKey);
    }
}
