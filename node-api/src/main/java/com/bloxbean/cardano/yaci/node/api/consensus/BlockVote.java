package com.bloxbean.cardano.yaci.node.api.consensus;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.Builder;
import lombok.Getter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * A validator's vote (signature) for a proposed block.
 * Serialized and transmitted as an AppMessage with topicId "{topic}::vote".
 */
@Getter
@Builder
public class BlockVote {
    private final byte[] blockHash;
    private final long blockNumber;
    private final String topicId;
    private final byte[] signerKey;
    private final byte[] signature;

    /**
     * Create a vote by signing the block hash with a keypair.
     */
    public static BlockVote create(byte[] blockHash, long blockNumber, String topicId, byte[] publicKey, byte[] signature) {
        return BlockVote.builder()
                .blockHash(blockHash)
                .blockNumber(blockNumber)
                .topicId(topicId)
                .signerKey(publicKey)
                .signature(signature)
                .build();
    }

    public String signerKeyHex() {
        return signerKey != null ? HexUtil.encodeHexString(signerKey) : null;
    }

    public String blockHashHex() {
        return blockHash != null ? HexUtil.encodeHexString(blockHash) : null;
    }

    /**
     * Wrap this vote as an AppMessage for gossip via Protocol 100.
     */
    public AppMessage toAppMessage(int ttlSeconds) {
        byte[] serialized = serialize();
        byte[] messageId = computeMessageId(serialized);
        long expiresAt = ttlSeconds > 0 ? (System.currentTimeMillis() / 1000) + ttlSeconds : 0;

        return AppMessage.builder()
                .messageId(messageId)
                .messageBody(serialized)
                .authMethod(0)
                .authProof(new byte[0])
                .topicId(topicId + "::vote")
                .expiresAt(expiresAt)
                .build();
    }

    public byte[] serialize() {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {
            writeByteArray(dos, blockHash);
            dos.writeLong(blockNumber);
            writeString(dos, topicId);
            writeByteArray(dos, signerKey);
            writeByteArray(dos, signature);
            dos.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize BlockVote", e);
        }
    }

    public static BlockVote deserialize(byte[] data) {
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data))) {
            byte[] blockHash = readByteArray(dis);
            long blockNumber = dis.readLong();
            String topicId = readString(dis);
            byte[] signerKey = readByteArray(dis);
            byte[] signature = readByteArray(dis);

            return BlockVote.builder()
                    .blockHash(blockHash)
                    .blockNumber(blockNumber)
                    .topicId(topicId)
                    .signerKey(signerKey)
                    .signature(signature)
                    .build();
        } catch (IOException e) {
            throw new RuntimeException("Failed to deserialize BlockVote", e);
        }
    }

    // --- Serialization helpers ---

    private static byte[] computeMessageId(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private static void writeByteArray(DataOutputStream dos, byte[] data) throws IOException {
        if (data == null) {
            dos.writeInt(-1);
        } else {
            dos.writeInt(data.length);
            dos.write(data);
        }
    }

    private static byte[] readByteArray(DataInputStream dis) throws IOException {
        int len = dis.readInt();
        if (len < 0) return null;
        byte[] data = new byte[len];
        dis.readFully(data);
        return data;
    }

    private static void writeString(DataOutputStream dos, String s) throws IOException {
        if (s == null) {
            dos.writeInt(-1);
        } else {
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            dos.writeInt(bytes.length);
            dos.write(bytes);
        }
    }

    private static String readString(DataInputStream dis) throws IOException {
        int len = dis.readInt();
        if (len < 0) return null;
        byte[] bytes = new byte[len];
        dis.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
