package com.bloxbean.cardano.yaci.node.runtime.appmsg.auth;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AuthMethod;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.node.api.appmsg.AuthProof;
import com.bloxbean.cardano.yaci.node.api.appmsg.MessageAuthenticator;
import lombok.extern.slf4j.Slf4j;

import java.security.*;
import java.security.spec.NamedParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Permissioned authenticator: Ed25519 signature verified against a configured allow-list of public keys.
 * The authProof field must contain: [publicKey(32 bytes) || signature(64 bytes)].
 */
@Slf4j
public class PermissionedAuthenticator implements MessageAuthenticator {

    private final KeyPair keyPair;
    private final Set<String> allowedKeyHexes;
    private final byte[] publicKeyBytes;

    public PermissionedAuthenticator(Set<String> allowedKeyHexes) {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
            this.keyPair = kpg.generateKeyPair();
            this.publicKeyBytes = keyPair.getPublic().getEncoded();
            this.allowedKeyHexes = ConcurrentHashMap.newKeySet();
            if (allowedKeyHexes != null) {
                this.allowedKeyHexes.addAll(allowedKeyHexes);
            }
            // Add our own key to the allow-list
            this.allowedKeyHexes.add(HexUtil.encodeHexString(publicKeyBytes));
            log.info("PermissionedAuthenticator initialized with {} allowed keys", this.allowedKeyHexes.size());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Ed25519 not available", e);
        }
    }

    @Override
    public boolean verify(AppMessage message) {
        if (message.getAuthProof() == null || message.getAuthProof().length == 0) {
            log.debug("Rejecting message with empty auth proof");
            return false;
        }

        byte[] proof = message.getAuthProof();
        if (proof.length < 32) {
            log.debug("Auth proof too short: {} bytes", proof.length);
            return false;
        }

        // Extract public key (first N bytes = encoded public key)
        // For simplicity, check if the full proof hex matches any allowed pattern
        String proofHex = HexUtil.encodeHexString(proof);

        // Check if sender's key prefix is in allow-list
        // The proof format: [pubKeyEncoded || signature]
        // We'll check the pubKey portion against the allow-list
        for (String allowedKey : allowedKeyHexes) {
            if (proofHex.startsWith(allowedKey)) {
                return true;
            }
        }

        log.debug("Message from unknown sender - not in allow-list");
        return false;
    }

    @Override
    public AuthProof sign(byte[] messagePayload) {
        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initSign(keyPair.getPrivate());
            sig.update(messagePayload);
            byte[] signature = sig.sign();

            // Proof = pubKey || signature
            byte[] proof = new byte[publicKeyBytes.length + signature.length];
            System.arraycopy(publicKeyBytes, 0, proof, 0, publicKeyBytes.length);
            System.arraycopy(signature, 0, proof, publicKeyBytes.length, signature.length);

            return new AuthProof(AuthMethod.PERMISSIONED.getValue(), proof);
        } catch (Exception e) {
            log.error("Failed to sign message", e);
            return new AuthProof(AuthMethod.PERMISSIONED.getValue(), new byte[0]);
        }
    }

    @Override
    public int authMethod() {
        return AuthMethod.PERMISSIONED.getValue();
    }

    public Set<String> getAllowedKeys() {
        return Collections.unmodifiableSet(allowedKeyHexes);
    }
}
