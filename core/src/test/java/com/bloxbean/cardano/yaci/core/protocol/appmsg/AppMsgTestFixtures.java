package com.bloxbean.cardano.yaci.core.protocol.appmsg;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AuthScheme;

import java.nio.charset.StandardCharsets;

/** Shared helpers for building well-formed envelope v2 messages in tests. */
public final class AppMsgTestFixtures {

    public static final String CHAIN = "test-chain";
    public static final byte[] SENDER = new byte[32]; // all-zero test sender key

    private AppMsgTestFixtures() {
    }

    /** Message with a valid content-derived id, expiring far in the future. */
    public static AppMessage message(String chainId, String topic, long senderSeq, byte[] body) {
        long expiresAt = System.currentTimeMillis() / 1000 + 600;
        return message(chainId, topic, senderSeq, body, expiresAt);
    }

    public static AppMessage message(String chainId, String topic, long senderSeq, byte[] body, long expiresAt) {
        byte[] messageId = AppMessage.computeMessageId(chainId, topic, SENDER, senderSeq, expiresAt, body);
        return AppMessage.builder()
                .messageId(messageId)
                .chainId(chainId)
                .topic(topic)
                .sender(SENDER)
                .senderSeq(senderSeq)
                .expiresAt(expiresAt)
                .body(body)
                .authScheme(AuthScheme.ED25519.getValue())
                .authProof(new byte[0])
                .build();
    }

    public static AppMessage message(long senderSeq, String body) {
        return message(CHAIN, "", senderSeq, body.getBytes(StandardCharsets.UTF_8));
    }
}
