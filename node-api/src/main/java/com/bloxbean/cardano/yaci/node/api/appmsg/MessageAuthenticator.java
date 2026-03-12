package com.bloxbean.cardano.yaci.node.api.appmsg;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;

/**
 * Pluggable authentication for app-layer messages.
 * Implementations verify incoming messages and sign outgoing ones.
 */
public interface MessageAuthenticator {

    /**
     * Verify that a received message has valid authentication.
     *
     * @return true if the message is authentic
     */
    boolean verify(AppMessage message);

    /**
     * Sign a message payload, producing an AuthProof.
     */
    AuthProof sign(byte[] messagePayload);

    /**
     * @return the auth method code this authenticator uses (matches AuthMethod enum)
     */
    int authMethod();
}
