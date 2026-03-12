package com.bloxbean.cardano.yaci.node.runtime.consensus;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.node.api.consensus.*;
import com.bloxbean.cardano.yaci.node.api.ledger.AppBlock;
import lombok.extern.slf4j.Slf4j;

import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.List;

/**
 * MultiSig consensus: requires n-of-m Ed25519 signatures to finalize.
 * Verifies each signature against the configured set of allowed public keys.
 */
@Slf4j
public class MultiSigConsensus implements AppConsensus {

    private final KeyPair localKeyPair;
    private final List<byte[]> allowedPublicKeys;
    private final ConsensusParams params;

    public MultiSigConsensus(List<byte[]> allowedPublicKeys, ConsensusParams params) {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
            this.localKeyPair = kpg.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Ed25519 not available", e);
        }
        this.allowedPublicKeys = allowedPublicKeys != null ? new ArrayList<>(allowedPublicKeys) : List.of();
        this.params = params;
        log.info("MultiSigConsensus initialized: threshold={}/{}, allowedKeys={}",
                params.getThreshold(), params.getTotalSigners(), this.allowedPublicKeys.size());
    }

    @Override
    public boolean canPropose() {
        return true;
    }

    @Override
    public ConsensusProof createProof(AppBlock block) {
        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initSign(localKeyPair.getPrivate());
            sig.update(block.getBlockHash());
            byte[] signature = sig.sign();
            byte[] publicKey = localKeyPair.getPublic().getEncoded();

            return ConsensusProof.builder()
                    .mode(ConsensusMode.MULTI_SIG)
                    .proposerKey(publicKey)
                    .signatures(new ArrayList<>(List.of(signature)))
                    .signerKeys(new ArrayList<>(List.of(publicKey)))
                    .threshold(params.getThreshold())
                    .build();
        } catch (Exception e) {
            log.error("Failed to create consensus proof", e);
            return ConsensusProof.builder()
                    .mode(ConsensusMode.MULTI_SIG)
                    .signatures(List.of())
                    .signerKeys(List.of())
                    .threshold(params.getThreshold())
                    .build();
        }
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

            if (verifySignature(block.getBlockHash(), signature, signerKey)) {
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

    /**
     * Get the local public key (encoded) for this node's signer.
     */
    public byte[] getLocalPublicKey() {
        return localKeyPair.getPublic().getEncoded();
    }

    private boolean isAllowedKey(byte[] publicKey) {
        if (allowedPublicKeys.isEmpty()) return true; // No restrictions
        for (byte[] allowed : allowedPublicKeys) {
            if (java.util.Arrays.equals(allowed, publicKey)) return true;
        }
        return false;
    }

    private boolean verifySignature(byte[] data, byte[] signature, byte[] publicKeyEncoded) {
        try {
            KeyFactory kf = KeyFactory.getInstance("Ed25519");
            PublicKey pubKey = kf.generatePublic(new X509EncodedKeySpec(publicKeyEncoded));
            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(pubKey);
            sig.update(data);
            return sig.verify(signature);
        } catch (Exception e) {
            log.debug("Signature verification failed: {}", e.getMessage());
            return false;
        }
    }
}
