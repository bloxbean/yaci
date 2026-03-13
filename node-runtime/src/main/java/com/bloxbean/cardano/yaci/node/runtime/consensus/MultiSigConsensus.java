package com.bloxbean.cardano.yaci.node.runtime.consensus;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.Keys;
import com.bloxbean.cardano.client.crypto.api.SigningProvider;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.node.api.consensus.*;
import com.bloxbean.cardano.yaci.node.api.ledger.AppBlock;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * MultiSig consensus: requires n-of-m Ed25519 signatures to finalize.
 * Verifies each signature against the configured set of allowed public keys.
 * Uses cardano-client-lib's SigningProvider for Ed25519 operations with raw 32-byte keys.
 */
@Slf4j
public class MultiSigConsensus implements AppConsensus {

    private final byte[] privateKey; // 32-byte raw Ed25519 seed
    private final byte[] publicKey;  // 32-byte raw Ed25519 public key
    private final List<byte[]> allowedPublicKeys; // 32-byte raw public keys
    private final ConsensusParams params;
    private final SigningProvider signingProvider;

    public MultiSigConsensus(List<byte[]> allowedPublicKeys, ConsensusParams params) {
        try {
            Keys keys = KeyGenUtil.generateKey();
            this.privateKey = keys.getSkey().getBytes();
            this.publicKey = keys.getVkey().getBytes();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate Ed25519 key pair", e);
        }
        this.allowedPublicKeys = allowedPublicKeys != null ? new ArrayList<>(allowedPublicKeys) : new ArrayList<>();
        this.allowedPublicKeys.sort((a, b) -> Arrays.compareUnsigned(a, b));
        this.params = params;
        this.signingProvider = CryptoConfiguration.INSTANCE.getSigningProvider();
        log.info("MultiSigConsensus initialized: threshold={}/{}, allowedKeys={}",
                params.getThreshold(), params.getTotalSigners(), this.allowedPublicKeys.size());
    }

    /**
     * Create with a pre-existing key pair (e.g., from config).
     */
    public MultiSigConsensus(byte[] privateKey, byte[] publicKey,
                             List<byte[]> allowedPublicKeys, ConsensusParams params) {
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.allowedPublicKeys = allowedPublicKeys != null ? new ArrayList<>(allowedPublicKeys) : new ArrayList<>();
        this.allowedPublicKeys.sort((a, b) -> Arrays.compareUnsigned(a, b));
        this.params = params;
        this.signingProvider = CryptoConfiguration.INSTANCE.getSigningProvider();
        log.info("MultiSigConsensus initialized with provided key: threshold={}/{}, allowedKeys={}",
                params.getThreshold(), params.getTotalSigners(), this.allowedPublicKeys.size());
    }

    @Override
    public boolean canPropose() {
        return true;
    }

    @Override
    public ConsensusProof createProof(AppBlock block) {
        byte[] signature = signingProvider.sign(block.getBlockHash(), privateKey);

        return ConsensusProof.builder()
                .mode(ConsensusMode.MULTI_SIG)
                .proposerKey(publicKey)
                .signatures(new ArrayList<>(List.of(signature)))
                .signerKeys(new ArrayList<>(List.of(publicKey)))
                .threshold(params.getThreshold())
                .build();
    }

    @Override
    public boolean verifyProof(AppBlock block, ConsensusProof proof) {
        if (proof == null || proof.getSignatures() == null || proof.getSignerKeys() == null) {
            return false;
        }
        if (proof.getSignatures().size() != proof.getSignerKeys().size()) {
            log.warn("Proof signature/key count mismatch");
            return false;
        }

        int validCount = 0;
        for (int i = 0; i < proof.getSignatures().size(); i++) {
            byte[] signature = proof.getSignatures().get(i);
            byte[] signerKey = proof.getSignerKeys().get(i);

            if (!isAllowedKey(signerKey)) {
                log.debug("Signer key not in allowed set: {}", HexUtil.encodeHexString(signerKey));
                continue;
            }

            if (signingProvider.verify(signature, block.getBlockHash(), signerKey)) {
                validCount++;
            }
        }

        boolean meets = validCount >= params.getThreshold();
        if (!meets) {
            log.debug("Consensus threshold not met: {}/{} (need {})",
                    validCount, proof.getSignatures().size(), params.getThreshold());
        }
        return meets;
    }

    @Override
    public ConsensusMode consensusMode() {
        return ConsensusMode.MULTI_SIG;
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

    @Override
    public boolean isProposerForBlock(long blockNumber) {
        if (allowedPublicKeys.isEmpty()) return true;
        int index = (int) (blockNumber % allowedPublicKeys.size());
        return Arrays.equals(publicKey, allowedPublicKeys.get(index));
    }

    @Override
    public boolean isExpectedProposer(long blockNumber, byte[] proposerKey) {
        if (allowedPublicKeys.isEmpty()) return true;
        int index = (int) (blockNumber % allowedPublicKeys.size());
        return Arrays.equals(allowedPublicKeys.get(index), proposerKey);
    }

    private boolean isAllowedKey(byte[] key) {
        if (allowedPublicKeys.isEmpty()) return true; // No restrictions
        for (byte[] allowed : allowedPublicKeys) {
            if (Arrays.equals(allowed, key)) return true;
        }
        return false;
    }
}
