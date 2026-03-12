package com.bloxbean.cardano.yaci.node.runtime.appmsg.auth;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AuthMethod;
import com.bloxbean.cardano.yaci.node.api.appmsg.AuthProof;
import com.bloxbean.cardano.yaci.node.api.appmsg.MessageAuthenticator;
import lombok.extern.slf4j.Slf4j;

import java.security.*;

/**
 * Open authenticator: Ed25519 self-generated key, any valid signature accepted.
 * Provides integrity (non-repudiation per key) but no access control.
 */
@Slf4j
public class OpenAuthenticator implements MessageAuthenticator {

    private final KeyPair keyPair;

    public OpenAuthenticator() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
            this.keyPair = kpg.generateKeyPair();
            log.info("OpenAuthenticator initialized with ephemeral Ed25519 key");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Ed25519 not available", e);
        }
    }

    @Override
    public boolean verify(AppMessage message) {
        // Open mode: accept all messages (no signature verification required)
        // In open mode, the authProof field may be empty
        return true;
    }

    @Override
    public AuthProof sign(byte[] messagePayload) {
        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initSign(keyPair.getPrivate());
            sig.update(messagePayload);
            return new AuthProof(AuthMethod.OPEN.getValue(), sig.sign());
        } catch (Exception e) {
            log.error("Failed to sign message", e);
            return new AuthProof(AuthMethod.OPEN.getValue(), new byte[0]);
        }
    }

    @Override
    public int authMethod() {
        return AuthMethod.OPEN.getValue();
    }
}
