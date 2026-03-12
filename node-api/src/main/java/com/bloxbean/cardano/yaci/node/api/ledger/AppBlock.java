package com.bloxbean.cardano.yaci.node.api.ledger;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.node.api.consensus.ConsensusProof;
import lombok.Builder;
import lombok.Getter;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * An app block: a batch of finalized app messages for a specific topic.
 * Each topic maintains its own independent block sequence.
 */
@Getter
@Builder
public class AppBlock {
    private final long blockNumber;
    private final String topicId;
    private final List<AppMessage> messages;
    private final byte[] stateHash;
    private final long timestamp;
    private final byte[] prevBlockHash;
    private final byte[] blockHash;
    private final ConsensusProof consensusProof;

    public int messageCount() {
        return messages != null ? messages.size() : 0;
    }

    public String blockHashHex() {
        return blockHash != null ? HexUtil.encodeHexString(blockHash) : null;
    }

    public String prevBlockHashHex() {
        return prevBlockHash != null ? HexUtil.encodeHexString(prevBlockHash) : null;
    }

    /**
     * Compute the state hash: SHA-256 of all message IDs concatenated.
     */
    public static byte[] computeStateHash(List<AppMessage> messages) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (AppMessage msg : messages) {
                digest.update(msg.getMessageId());
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Compute the block hash: SHA-256 of (blockNumber + topicId + stateHash + prevBlockHash + timestamp).
     */
    public static byte[] computeBlockHash(long blockNumber, String topicId, byte[] stateHash,
                                          byte[] prevBlockHash, long timestamp) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(ByteBuffer.allocate(8).putLong(blockNumber).array());
            digest.update(topicId.getBytes(StandardCharsets.UTF_8));
            if (stateHash != null) digest.update(stateHash);
            if (prevBlockHash != null) digest.update(prevBlockHash);
            digest.update(ByteBuffer.allocate(8).putLong(timestamp).array());
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
