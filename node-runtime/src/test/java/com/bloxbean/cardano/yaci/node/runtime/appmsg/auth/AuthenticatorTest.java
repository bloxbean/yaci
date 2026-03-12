package com.bloxbean.cardano.yaci.node.runtime.appmsg.auth;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AuthMethod;
import com.bloxbean.cardano.yaci.node.api.appmsg.AuthProof;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AuthenticatorTest {

    // --- OpenAuthenticator ---

    @Test
    void openAuthenticator_acceptsAllMessages() {
        var auth = new OpenAuthenticator();
        AppMessage msg = AppMessage.builder()
                .messageId(new byte[]{0x01})
                .messageBody(new byte[]{0x02})
                .authMethod(AuthMethod.OPEN.getValue())
                .authProof(new byte[0])
                .topicId("test")
                .expiresAt(0)
                .build();
        assertThat(auth.verify(msg)).isTrue();
    }

    @Test
    void openAuthenticator_acceptsNullProof() {
        var auth = new OpenAuthenticator();
        AppMessage msg = AppMessage.builder()
                .messageId(new byte[]{0x01})
                .messageBody(new byte[]{0x02})
                .authMethod(0)
                .authProof(null)
                .topicId("test")
                .expiresAt(0)
                .build();
        assertThat(auth.verify(msg)).isTrue();
    }

    @Test
    void openAuthenticator_signsAndReturnsProof() {
        var auth = new OpenAuthenticator();
        AuthProof proof = auth.sign(new byte[]{0x01, 0x02, 0x03});
        assertThat(proof).isNotNull();
        assertThat(proof.getAuthMethod()).isEqualTo(AuthMethod.OPEN.getValue());
        assertThat(proof.getProofBytes()).isNotNull();
        assertThat(proof.getProofBytes().length).isGreaterThan(0);
    }

    @Test
    void openAuthenticator_authMethodIsOpen() {
        var auth = new OpenAuthenticator();
        assertThat(auth.authMethod()).isEqualTo(AuthMethod.OPEN.getValue());
    }

    // --- PermissionedAuthenticator ---

    @Test
    void permissionedAuthenticator_signAndVerifyRoundTrip() {
        var auth = new PermissionedAuthenticator(null);

        // Sign a payload
        AuthProof proof = auth.sign(new byte[]{0x01, 0x02, 0x03});
        assertThat(proof.getAuthMethod()).isEqualTo(AuthMethod.PERMISSIONED.getValue());
        assertThat(proof.getProofBytes().length).isGreaterThan(0);

        // Verify with own proof (own key is auto-added to allow-list)
        AppMessage msg = AppMessage.builder()
                .messageId(new byte[]{0x01})
                .messageBody(new byte[]{0x01, 0x02, 0x03})
                .authMethod(AuthMethod.PERMISSIONED.getValue())
                .authProof(proof.getProofBytes())
                .topicId("test")
                .expiresAt(0)
                .build();
        assertThat(auth.verify(msg)).isTrue();
    }

    @Test
    void permissionedAuthenticator_rejectsEmptyProof() {
        var auth = new PermissionedAuthenticator(null);
        AppMessage msg = AppMessage.builder()
                .messageId(new byte[]{0x01})
                .messageBody(new byte[]{0x01})
                .authMethod(AuthMethod.PERMISSIONED.getValue())
                .authProof(new byte[0])
                .topicId("test")
                .expiresAt(0)
                .build();
        assertThat(auth.verify(msg)).isFalse();
    }

    @Test
    void permissionedAuthenticator_rejectsUnknownKey() {
        var auth = new PermissionedAuthenticator(null);
        // Random bytes not matching any allowed key
        AppMessage msg = AppMessage.builder()
                .messageId(new byte[]{0x01})
                .messageBody(new byte[]{0x01})
                .authMethod(AuthMethod.PERMISSIONED.getValue())
                .authProof(new byte[64])  // 64 zero bytes - won't match any key
                .topicId("test")
                .expiresAt(0)
                .build();
        assertThat(auth.verify(msg)).isFalse();
    }

    @Test
    void permissionedAuthenticator_crossNodeVerification() {
        // Node A creates authenticator
        var authA = new PermissionedAuthenticator(null);
        // Get Node A's public key hex to add to Node B's allow-list
        String keyA = authA.getAllowedKeys().iterator().next();

        // Node B creates authenticator with Node A's key allowed
        var authB = new PermissionedAuthenticator(Set.of(keyA));

        // Node A signs a message
        AuthProof proof = authA.sign(new byte[]{0x10, 0x20});
        AppMessage msg = AppMessage.builder()
                .messageId(new byte[]{0x01})
                .messageBody(new byte[]{0x10, 0x20})
                .authMethod(AuthMethod.PERMISSIONED.getValue())
                .authProof(proof.getProofBytes())
                .topicId("test")
                .expiresAt(0)
                .build();

        // Node B can verify it
        assertThat(authB.verify(msg)).isTrue();
    }

    @Test
    void permissionedAuthenticator_authMethodIsPermissioned() {
        var auth = new PermissionedAuthenticator(null);
        assertThat(auth.authMethod()).isEqualTo(AuthMethod.PERMISSIONED.getValue());
    }

    @Test
    void permissionedAuthenticator_allowedKeysIncludesOwnKey() {
        var auth = new PermissionedAuthenticator(Set.of("aabbccdd"));
        // Should have own key + provided key
        assertThat(auth.getAllowedKeys()).hasSize(2);
        assertThat(auth.getAllowedKeys()).contains("aabbccdd");
    }
}
