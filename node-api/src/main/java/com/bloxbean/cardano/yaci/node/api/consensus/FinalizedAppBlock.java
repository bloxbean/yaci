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
 * A finalized app block with aggregated consensus proof, ready for distribution.
 * Serialized and transmitted as an AppMessage with topicId "{topic}::finalized".
 */
@Getter
@Builder
public class FinalizedAppBlock {
    private final AppBlock block;
    private final ConsensusProof proof;

    /**
     * Wrap this finalized block as an AppMessage for gossip via Protocol 100.
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
                .topicId(block.getTopicId() + "::finalized")
                .expiresAt(expiresAt)
                .build();
    }

    public byte[] serialize() {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {
            // Serialize the block
            dos.writeLong(block.getBlockNumber());
            writeString(dos, block.getTopicId());
            writeByteArray(dos, block.getStateHash());
            dos.writeLong(block.getTimestamp());
            writeByteArray(dos, block.getPrevBlockHash());
            writeByteArray(dos, block.getBlockHash());

            List<AppMessage> messages = block.getMessages() != null ? block.getMessages() : List.of();
            dos.writeInt(messages.size());
            for (AppMessage msg : messages) {
                writeByteArray(dos, msg.getMessageId());
                writeByteArray(dos, msg.getMessageBody());
                dos.writeInt(msg.getAuthMethod());
                writeByteArray(dos, msg.getAuthProof());
                writeString(dos, msg.getTopicId());
                dos.writeLong(msg.getExpiresAt());
            }

            // Serialize the proof
            dos.writeInt(proof.getMode().getValue());
            dos.writeInt(proof.getThreshold());
            writeByteArray(dos, proof.getProposerKey());

            List<byte[]> sigs = proof.getSignatures() != null ? proof.getSignatures() : List.of();
            dos.writeInt(sigs.size());
            for (byte[] sig : sigs) {
                writeByteArray(dos, sig);
            }

            List<byte[]> keys = proof.getSignerKeys() != null ? proof.getSignerKeys() : List.of();
            dos.writeInt(keys.size());
            for (byte[] key : keys) {
                writeByteArray(dos, key);
            }

            dos.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize FinalizedAppBlock", e);
        }
    }

    public static FinalizedAppBlock deserialize(byte[] data) {
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data))) {
            // Deserialize block
            long blockNumber = dis.readLong();
            String topicId = readString(dis);
            byte[] stateHash = readByteArray(dis);
            long timestamp = dis.readLong();
            byte[] prevBlockHash = readByteArray(dis);
            byte[] blockHash = readByteArray(dis);

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

            // Deserialize proof
            ConsensusMode mode = ConsensusMode.fromValue(dis.readInt());
            int threshold = dis.readInt();
            byte[] proposerKey = readByteArray(dis);

            int sigCount = dis.readInt();
            List<byte[]> sigs = new ArrayList<>(sigCount);
            for (int i = 0; i < sigCount; i++) {
                sigs.add(readByteArray(dis));
            }

            int keyCount = dis.readInt();
            List<byte[]> signerKeys = new ArrayList<>(keyCount);
            for (int i = 0; i < keyCount; i++) {
                signerKeys.add(readByteArray(dis));
            }

            ConsensusProof proof = ConsensusProof.builder()
                    .mode(mode)
                    .threshold(threshold)
                    .proposerKey(proposerKey)
                    .signatures(sigs)
                    .signerKeys(signerKeys)
                    .build();

            AppBlock block = AppBlock.builder()
                    .blockNumber(blockNumber)
                    .topicId(topicId)
                    .stateHash(stateHash)
                    .timestamp(timestamp)
                    .prevBlockHash(prevBlockHash)
                    .blockHash(blockHash)
                    .messages(messages)
                    .consensusProof(proof)
                    .build();

            return FinalizedAppBlock.builder()
                    .block(block)
                    .proof(proof)
                    .build();
        } catch (IOException e) {
            throw new RuntimeException("Failed to deserialize FinalizedAppBlock", e);
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
