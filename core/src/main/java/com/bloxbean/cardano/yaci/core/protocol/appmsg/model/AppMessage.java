package com.bloxbean.cardano.yaci.core.protocol.appmsg.model;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.Arrays;

/**
 * Application message envelope (v2) for the app-chain gossip protocols.
 * <p>
 * CBOR: [version(uint), messageId(bstr .size 32), chainId(tstr), topic(tstr),
 *        sender(bstr), senderSeq(uint), expiresAt(uint), body(bstr),
 *        [authScheme(uint), authProof(bstr)]]
 * <p>
 * {@code messageId} is content-derived: blake2b-256 over the CBOR encoding of the
 * signed body {@code [chainId, topic, sender, senderSeq, expiresAt, body]}. Receivers
 * MUST recompute and compare it. {@code body} is an opaque application payload —
 * never interpreted by the transport or sequencing layers.
 */
@Getter
@AllArgsConstructor
@Builder
public class AppMessage {
    public static final int ENVELOPE_VERSION = 2;

    @Builder.Default
    private final int version = ENVELOPE_VERSION;
    private final byte[] messageId;
    private final String chainId;
    private final String topic;
    private final byte[] sender;
    private final long senderSeq;
    private final long expiresAt;
    private final byte[] body;
    private final int authScheme;
    private final byte[] authProof;

    /** CBOR bytes of the signed body — the input to both signing and messageId derivation. */
    public byte[] signedBodyBytes() {
        return signedBodyBytes(chainId, topic, sender, senderSeq, expiresAt, body);
    }

    public static byte[] signedBodyBytes(String chainId, String topic, byte[] sender,
                                         long senderSeq, long expiresAt, byte[] body) {
        Array arr = new Array();
        arr.add(new UnicodeString(chainId != null ? chainId : ""));
        arr.add(new UnicodeString(topic != null ? topic : ""));
        arr.add(new ByteString(sender != null ? sender : new byte[0]));
        arr.add(new UnsignedInteger(senderSeq));
        arr.add(new UnsignedInteger(expiresAt));
        arr.add(new ByteString(body != null ? body : new byte[0]));
        return CborSerializationUtil.serialize(arr);
    }

    public static byte[] computeMessageId(String chainId, String topic, byte[] sender,
                                          long senderSeq, long expiresAt, byte[] body) {
        return Blake2bUtil.blake2bHash256(signedBodyBytes(chainId, topic, sender, senderSeq, expiresAt, body));
    }

    /** Recompute the content-derived id and compare with the embedded one. */
    public boolean hasValidMessageId() {
        if (messageId == null || messageId.length != 32)
            return false;
        return Arrays.equals(messageId,
                computeMessageId(chainId, topic, sender, senderSeq, expiresAt, body));
    }

    public boolean isExpired(long nowEpochSeconds) {
        return expiresAt > 0 && nowEpochSeconds > expiresAt;
    }

    public int getSize() {
        return body != null ? body.length : 0;
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
                + ", chain=" + chainId
                + ", topic=" + topic
                + ", seq=" + senderSeq
                + ", size=" + getSize()
                + ", scheme=" + authScheme
                + ", expiresAt=" + expiresAt + "}";
    }
}
