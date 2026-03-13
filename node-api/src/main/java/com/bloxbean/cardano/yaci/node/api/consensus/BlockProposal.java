package com.bloxbean.cardano.yaci.node.api.consensus;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.node.api.ledger.AppBlock;
import lombok.Builder;
import lombok.Getter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * A block proposal sent by the proposer during a consensus round.
 * Serialized and transmitted as an AppMessage with topicId "{topic}::proposal".
 */
@Getter
@Builder
public class BlockProposal {
    private final long blockNumber;
    private final String topicId;
    private final long timestamp;
    private final byte[] prevBlockHash;
    private final byte[] stateHash;
    private final byte[] blockHash;
    private final byte[] proposerKey;
    private final byte[] proposerSignature;
    private final List<AppMessage> messages;

    /**
     * Create a BlockProposal from a candidate AppBlock and the proposer's key + signature.
     */
    public static BlockProposal fromAppBlock(AppBlock block, byte[] proposerKey, byte[] proposerSignature) {
        return BlockProposal.builder()
                .blockNumber(block.getBlockNumber())
                .topicId(block.getTopicId())
                .timestamp(block.getTimestamp())
                .prevBlockHash(block.getPrevBlockHash())
                .stateHash(block.getStateHash())
                .blockHash(block.getBlockHash())
                .proposerKey(proposerKey)
                .proposerSignature(proposerSignature)
                .messages(block.getMessages())
                .build();
    }

    /**
     * Convert this proposal to an AppBlock (without consensus proof).
     */
    public AppBlock toAppBlock() {
        return AppBlock.builder()
                .blockNumber(blockNumber)
                .topicId(topicId)
                .timestamp(timestamp)
                .prevBlockHash(prevBlockHash)
                .stateHash(stateHash)
                .blockHash(blockHash)
                .messages(messages != null ? messages : List.of())
                .build();
    }

    /**
     * Wrap this proposal as an AppMessage for gossip via Protocol 100.
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
                .topicId(topicId + "::proposal")
                .expiresAt(expiresAt)
                .build();
    }

    public byte[] serialize() {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeLong(blockNumber);
            writeString(dos, topicId);
            dos.writeLong(timestamp);
            writeByteArray(dos, prevBlockHash);
            writeByteArray(dos, stateHash);
            writeByteArray(dos, blockHash);
            writeByteArray(dos, proposerKey);
            writeByteArray(dos, proposerSignature);

            List<AppMessage> msgs = messages != null ? messages : List.of();
            dos.writeInt(msgs.size());
            for (AppMessage msg : msgs) {
                writeByteArray(dos, msg.getMessageId());
                writeByteArray(dos, msg.getMessageBody());
                dos.writeInt(msg.getAuthMethod());
                writeByteArray(dos, msg.getAuthProof());
                writeString(dos, msg.getTopicId());
                dos.writeLong(msg.getExpiresAt());
            }

            dos.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize BlockProposal", e);
        }
    }

    public static BlockProposal deserialize(byte[] data) {
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data))) {
            long blockNumber = dis.readLong();
            String topicId = readString(dis);
            long timestamp = dis.readLong();
            byte[] prevBlockHash = readByteArray(dis);
            byte[] stateHash = readByteArray(dis);
            byte[] blockHash = readByteArray(dis);
            byte[] proposerKey = readByteArray(dis);
            byte[] proposerSignature = readByteArray(dis);

            int msgCount = dis.readInt();
            List<AppMessage> messages = new ArrayList<>(msgCount);
            for (int i = 0; i < msgCount; i++) {
                messages.add(AppMessage.builder()
                        .messageId(readByteArray(dis))
                        .messageBody(readByteArray(dis))
                        .authMethod(dis.readInt())
                        .authProof(readByteArray(dis))
                        .topicId(readString(dis))
                        .expiresAt(dis.readLong())
                        .build());
            }

            return BlockProposal.builder()
                    .blockNumber(blockNumber)
                    .topicId(topicId)
                    .timestamp(timestamp)
                    .prevBlockHash(prevBlockHash)
                    .stateHash(stateHash)
                    .blockHash(blockHash)
                    .proposerKey(proposerKey)
                    .proposerSignature(proposerSignature)
                    .messages(messages)
                    .build();
        } catch (IOException e) {
            throw new RuntimeException("Failed to deserialize BlockProposal", e);
        }
    }

    // --- Serialization helpers (matching RocksDBAppLedger pattern) ---

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
