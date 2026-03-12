package com.bloxbean.cardano.yaci.core.protocol.appmsg.model;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.Arrays;

/**
 * Full application message envelope for gossip protocols.
 * <p>
 * CBOR: [messageId(bstr), messageBody(bstr), authMethod(uint), authProof(bstr), topicId(tstr), expiresAt(uint)]
 */
@Getter
@AllArgsConstructor
@Builder
public class AppMessage {
    private final byte[] messageId;
    private final byte[] messageBody;
    private final int authMethod;
    private final byte[] authProof;
    private final String topicId;
    private final long expiresAt;

    public int getSize() {
        return messageBody != null ? messageBody.length : 0;
    }

    public String getMessageIdHex() {
        return HexUtil.encodeHexString(messageId);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AppMessage that = (AppMessage) o;
        return Arrays.equals(messageId, that.messageId);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(messageId);
    }

    @Override
    public String toString() {
        return "AppMessage{id=" + getMessageIdHex()
                + ", topic=" + topicId
                + ", size=" + getSize()
                + ", auth=" + authMethod
                + ", expiresAt=" + expiresAt + "}";
    }
}
