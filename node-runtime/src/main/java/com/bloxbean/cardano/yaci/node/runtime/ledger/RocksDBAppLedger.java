package com.bloxbean.cardano.yaci.node.runtime.ledger;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.node.api.consensus.ConsensusMode;
import com.bloxbean.cardano.yaci.node.api.consensus.ConsensusProof;
import com.bloxbean.cardano.yaci.node.api.ledger.AppBlock;
import com.bloxbean.cardano.yaci.node.api.ledger.AppLedger;
import com.bloxbean.cardano.yaci.node.api.ledger.AppLedgerTip;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * RocksDB-backed implementation of AppLedger.
 * Uses a separate RocksDB instance from the L1 chain state.
 */
@Slf4j
public class RocksDBAppLedger implements AppLedger {

    private RocksDB db;
    private ColumnFamilyHandle blocksHandle;
    private ColumnFamilyHandle stateHandle;
    private ColumnFamilyHandle proofsHandle;
    private ColumnFamilyHandle topicsHandle;

    private final String dbPath;

    static {
        RocksDB.loadLibrary();
    }

    public RocksDBAppLedger(String dbPath) {
        this.dbPath = dbPath;
        openDb();
    }

    private void openDb() {
        try (DBOptions dbOptions = new DBOptions()
                .setCreateIfMissing(true)
                .setCreateMissingColumnFamilies(true)) {

            List<ColumnFamilyDescriptor> cfDescriptors = Arrays.asList(
                    new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY),
                    new ColumnFamilyDescriptor(AppLedgerCfNames.APP_BLOCKS.getBytes()),
                    new ColumnFamilyDescriptor(AppLedgerCfNames.APP_STATE.getBytes()),
                    new ColumnFamilyDescriptor(AppLedgerCfNames.APP_CONSENSUS_PROOFS.getBytes()),
                    new ColumnFamilyDescriptor(AppLedgerCfNames.APP_TOPICS.getBytes())
            );

            List<ColumnFamilyHandle> cfHandles = new ArrayList<>();
            db = RocksDB.open(dbOptions, dbPath, cfDescriptors, cfHandles);

            blocksHandle = cfHandles.get(1);
            stateHandle = cfHandles.get(2);
            proofsHandle = cfHandles.get(3);
            topicsHandle = cfHandles.get(4);

            log.info("App ledger RocksDB initialized at: {}", dbPath);
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to open app ledger RocksDB at " + dbPath, e);
        }
    }

    @Override
    public void storeBlock(AppBlock block) {
        try (WriteBatch batch = new WriteBatch();
             WriteOptions wo = new WriteOptions()) {

            byte[] blockKey = blockKey(block.getTopicId(), block.getBlockNumber());
            batch.put(blocksHandle, blockKey, serializeBlock(block));

            // Update tip state
            byte[] stateKey = block.getTopicId().getBytes(StandardCharsets.UTF_8);
            AppLedgerTip tip = AppLedgerTip.builder()
                    .topicId(block.getTopicId())
                    .blockNumber(block.getBlockNumber())
                    .blockHash(block.getBlockHash())
                    .timestamp(block.getTimestamp())
                    .totalMessages(0) // Calculated on read
                    .build();
            batch.put(stateHandle, stateKey, serializeTip(tip));

            // Store consensus proof if present
            if (block.getConsensusProof() != null) {
                byte[] proofKey = blockKey(block.getTopicId(), block.getBlockNumber());
                batch.put(proofsHandle, proofKey, serializeProof(block.getConsensusProof()));
            }

            db.write(wo, batch);

            log.debug("App block stored: topic={}, block={}, messages={}",
                    block.getTopicId(), block.getBlockNumber(), block.messageCount());
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to store app block", e);
        }
    }

    @Override
    public Optional<AppBlock> getBlock(String topicId, long blockNumber) {
        try {
            byte[] key = blockKey(topicId, blockNumber);
            byte[] data = db.get(blocksHandle, key);
            if (data == null) return Optional.empty();

            AppBlock block = deserializeBlock(data);

            // Attach consensus proof if stored separately
            byte[] proofData = db.get(proofsHandle, key);
            if (proofData != null) {
                block = AppBlock.builder()
                        .blockNumber(block.getBlockNumber())
                        .topicId(block.getTopicId())
                        .messages(block.getMessages())
                        .stateHash(block.getStateHash())
                        .timestamp(block.getTimestamp())
                        .prevBlockHash(block.getPrevBlockHash())
                        .blockHash(block.getBlockHash())
                        .consensusProof(deserializeProof(proofData))
                        .build();
            }

            return Optional.of(block);
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to get app block", e);
        }
    }

    @Override
    public Optional<AppBlock> getLatestBlock(String topicId) {
        return getTip(topicId).flatMap(tip -> getBlock(topicId, tip.getBlockNumber()));
    }

    @Override
    public Optional<AppLedgerTip> getTip(String topicId) {
        try {
            byte[] data = db.get(stateHandle, topicId.getBytes(StandardCharsets.UTF_8));
            if (data == null) return Optional.empty();
            return Optional.of(deserializeTip(data));
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to get app ledger tip", e);
        }
    }

    @Override
    public List<AppBlock> getBlocks(String topicId, long fromBlock, long toBlock) {
        List<AppBlock> result = new ArrayList<>();
        for (long i = fromBlock; i <= toBlock; i++) {
            getBlock(topicId, i).ifPresent(result::add);
        }
        return result;
    }

    @Override
    public void storeConsensusProof(String topicId, long blockNumber, ConsensusProof proof) {
        try {
            db.put(proofsHandle, blockKey(topicId, blockNumber), serializeProof(proof));
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to store consensus proof", e);
        }
    }

    @Override
    public Optional<ConsensusProof> getConsensusProof(String topicId, long blockNumber) {
        try {
            byte[] data = db.get(proofsHandle, blockKey(topicId, blockNumber));
            if (data == null) return Optional.empty();
            return Optional.of(deserializeProof(data));
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to get consensus proof", e);
        }
    }

    @Override
    public void close() {
        if (blocksHandle != null) blocksHandle.close();
        if (stateHandle != null) stateHandle.close();
        if (proofsHandle != null) proofsHandle.close();
        if (topicsHandle != null) topicsHandle.close();
        if (db != null) db.close();
        log.info("App ledger RocksDB closed");
    }

    // --- Key helpers ---

    private static byte[] blockKey(String topicId, long blockNumber) {
        byte[] topicBytes = topicId.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(topicBytes.length + 1 + 8);
        buf.put(topicBytes);
        buf.put((byte) ':');
        buf.putLong(blockNumber);
        return buf.array();
    }

    // --- Serialization (Java ObjectStream — simple, reliable) ---

    private byte[] serializeBlock(AppBlock block) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeLong(block.getBlockNumber());
            writeString(dos, block.getTopicId());
            writeByteArray(dos, block.getStateHash());
            dos.writeLong(block.getTimestamp());
            writeByteArray(dos, block.getPrevBlockHash());
            writeByteArray(dos, block.getBlockHash());

            // Messages
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

            dos.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize app block", e);
        }
    }

    private AppBlock deserializeBlock(byte[] data) {
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data))) {
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

            return AppBlock.builder()
                    .blockNumber(blockNumber)
                    .topicId(topicId)
                    .stateHash(stateHash)
                    .timestamp(timestamp)
                    .prevBlockHash(prevBlockHash)
                    .blockHash(blockHash)
                    .messages(messages)
                    .build();
        } catch (IOException e) {
            throw new RuntimeException("Failed to deserialize app block", e);
        }
    }

    private byte[] serializeTip(AppLedgerTip tip) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {
            writeString(dos, tip.getTopicId());
            dos.writeLong(tip.getBlockNumber());
            writeByteArray(dos, tip.getBlockHash());
            dos.writeLong(tip.getTimestamp());
            dos.writeLong(tip.getTotalMessages());
            dos.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize tip", e);
        }
    }

    private AppLedgerTip deserializeTip(byte[] data) {
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data))) {
            return AppLedgerTip.builder()
                    .topicId(readString(dis))
                    .blockNumber(dis.readLong())
                    .blockHash(readByteArray(dis))
                    .timestamp(dis.readLong())
                    .totalMessages(dis.readLong())
                    .build();
        } catch (IOException e) {
            throw new RuntimeException("Failed to deserialize tip", e);
        }
    }

    private byte[] serializeProof(ConsensusProof proof) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {
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
            throw new RuntimeException("Failed to serialize proof", e);
        }
    }

    private ConsensusProof deserializeProof(byte[] data) {
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data))) {
            ConsensusMode mode = ConsensusMode.fromValue(dis.readInt());
            int threshold = dis.readInt();
            byte[] proposerKey = readByteArray(dis);

            int sigCount = dis.readInt();
            List<byte[]> sigs = new ArrayList<>(sigCount);
            for (int i = 0; i < sigCount; i++) {
                sigs.add(readByteArray(dis));
            }

            int keyCount = dis.readInt();
            List<byte[]> keys = new ArrayList<>(keyCount);
            for (int i = 0; i < keyCount; i++) {
                keys.add(readByteArray(dis));
            }

            return ConsensusProof.builder()
                    .mode(mode)
                    .threshold(threshold)
                    .proposerKey(proposerKey)
                    .signatures(sigs)
                    .signerKeys(keys)
                    .build();
        } catch (IOException e) {
            throw new RuntimeException("Failed to deserialize proof", e);
        }
    }

    // --- Low-level helpers ---

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
