package com.bloxbean.cardano.yaci.node.chain;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockHeaderSerializer;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Direct RocksDB implementation of ChainState
 * This implementation uses RocksDB directly without any caching layer.
 */
@Slf4j
public class DirectRocksDBChainState implements ChainState {
    
    private static final byte[] TIP_KEY = "tip".getBytes(StandardCharsets.UTF_8);
    private static final byte[] FIRST_BLOCK_KEY = "first_block".getBytes(StandardCharsets.UTF_8);
    
    private final RocksDB db;
    private final String dbPath;
    
    // Column families
    private final ColumnFamilyHandle blocksHandle;
    private final ColumnFamilyHandle headersHandle;
    private final ColumnFamilyHandle hashByNumberHandle;
    private final ColumnFamilyHandle numberBySlotHandle;
    private final ColumnFamilyHandle slotByNumberHandle;
    private final ColumnFamilyHandle metadataHandle;
    
    static {
        RocksDB.loadLibrary();
    }
    
    public DirectRocksDBChainState(String dbPath) {
        this.dbPath = dbPath;
        
        try {
            // Configure RocksDB
            final DBOptions dbOptions = new DBOptions()
                .setCreateIfMissing(true)
                .setCreateMissingColumnFamilies(true)
                .setMaxOpenFiles(100)
                .setKeepLogFileNum(5);
            
            // Column family descriptors
            final List<ColumnFamilyDescriptor> cfDescriptors = Arrays.asList(
                new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY),
                new ColumnFamilyDescriptor("blocks".getBytes()),
                new ColumnFamilyDescriptor("headers".getBytes()),
                new ColumnFamilyDescriptor("hash_by_number".getBytes()),
                new ColumnFamilyDescriptor("number_by_slot".getBytes()),
                new ColumnFamilyDescriptor("slot_by_number".getBytes()),
                new ColumnFamilyDescriptor("metadata".getBytes())
            );
            
            // Open database
            final List<ColumnFamilyHandle> cfHandles = new ArrayList<>();
            db = RocksDB.open(dbOptions, dbPath, cfDescriptors, cfHandles);
            
            // Assign handles (skip default at index 0)
            blocksHandle = cfHandles.get(1);
            headersHandle = cfHandles.get(2);
            hashByNumberHandle = cfHandles.get(3);
            numberBySlotHandle = cfHandles.get(4);
            slotByNumberHandle = cfHandles.get(5);
            metadataHandle = cfHandles.get(6);
            
            log.info("RocksDB initialized at: {}", dbPath);
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize RocksDB", e);
        }
    }
    
    @Override
    public void storeBlock(byte[] blockHash, long blockNumber, long slot, byte[] block) {
        try {
            // Use write batch for atomic updates
            try (WriteBatch batch = new WriteBatch()) {
                // Store block
                batch.put(blocksHandle, blockHash, block);
                
                // Store mappings
                batch.put(hashByNumberHandle, longToBytes(blockNumber), blockHash);
                batch.put(numberBySlotHandle, longToBytes(slot), longToBytes(blockNumber));
                batch.put(slotByNumberHandle, longToBytes(blockNumber), longToBytes(slot));
                
                // Update tip
                ChainTip tip = new ChainTip(slot, blockHash, blockNumber);
                batch.put(metadataHandle, TIP_KEY, serializeChainTip(tip));
                
                // Update first block if needed
                Point firstBlock = getFirstBlock();
                if (firstBlock == null || slot < firstBlock.getSlot()) {
                    batch.put(metadataHandle, FIRST_BLOCK_KEY, 
                        serializePoint(new Point(slot, HexUtil.encodeHexString(blockHash))));
                }
                
                // Write batch atomically
                db.write(new WriteOptions(), batch);
                
                log.debug("Stored block: number={}, slot={}, hash={}", 
                    blockNumber, slot, HexUtil.encodeHexString(blockHash));
            }
        } catch (Exception e) {
            log.error("Failed to store block: slot={}, blockNumber={}", slot, blockNumber, e);
            throw new RuntimeException("Failed to store block", e);
        }
    }
    
    @Override
    public byte[] getBlock(byte[] blockHash) {
        try {
            return db.get(blocksHandle, blockHash);
        } catch (Exception e) {
            log.error("Failed to get block", e);
            return null;
        }
    }
    
    @Override
    public void storeBlockHeader(byte[] blockHash, byte[] blockHeader) {
        try {
            // Try to extract slot and block number from header
            Long slot = null;
            Long blockNumber = null;
            
            try {
                // Parse the header to extract slot and block number
                DataItem[] dataItems = CborSerializationUtil.deserialize(blockHeader);
                if (dataItems != null && dataItems.length > 0) {
                    // Try to parse as a block header array
                    if (dataItems[0] instanceof Array) {
                        Array headerArray = (Array) dataItems[0];
                        BlockHeader parsedHeader = BlockHeaderSerializer.INSTANCE.getBlockHeaderFromHeaderArray(headerArray);
                        if (parsedHeader != null && parsedHeader.getHeaderBody() != null) {
                            slot = parsedHeader.getHeaderBody().getSlot();
                            blockNumber = parsedHeader.getHeaderBody().getBlockNumber();
                        }
                    } else if (dataItems[0] instanceof UnsignedInteger) {
                        // Handle Byron blocks
                        // For Byron, we might need different parsing logic
                        log.debug("Byron header detected, skipping tip update for now");
                    }
                }
            } catch (Exception e) {
                log.debug("Could not parse header for slot/blockNumber extraction: {}", e.getMessage());
            }
            
            // Use write batch for atomic updates
            try (WriteBatch batch = new WriteBatch()) {
                // Store header
                batch.put(headersHandle, blockHash, blockHeader);
                
                // If we successfully extracted slot and block number, update indices and tip
                if (slot != null && blockNumber != null) {
                    // Store mappings
                    batch.put(hashByNumberHandle, longToBytes(blockNumber), blockHash);
                    batch.put(numberBySlotHandle, longToBytes(slot), longToBytes(blockNumber));
                    batch.put(slotByNumberHandle, longToBytes(blockNumber), longToBytes(slot));
                    
                    // Update tip if this is a newer block
                    ChainTip currentTip = getTip();
                    if (currentTip == null || slot > currentTip.getSlot()) {
                        ChainTip newTip = new ChainTip(slot, blockHash, blockNumber);
                        batch.put(metadataHandle, TIP_KEY, serializeChainTip(newTip));
                        log.debug("Updated tip from header: slot={}, blockNumber={}", slot, blockNumber);
                    }
                    
                    // Update first block if needed
                    Point firstBlock = getFirstBlock();
                    if (firstBlock == null || slot < firstBlock.getSlot()) {
                        batch.put(metadataHandle, FIRST_BLOCK_KEY, 
                            serializePoint(new Point(slot, HexUtil.encodeHexString(blockHash))));
                    }
                }
                
                // Write batch atomically
                db.write(new WriteOptions(), batch);
                
                log.debug("Stored header: hash={}, extracted slot={}, blockNumber={}", 
                    HexUtil.encodeHexString(blockHash), slot, blockNumber);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to store block header", e);
        }
    }
    
    @Override
    public byte[] getBlockHeader(byte[] blockHash) {
        try {
            return db.get(headersHandle, blockHash);
        } catch (Exception e) {
            log.error("Failed to get block header", e);
            return null;
        }
    }
    
    @Override
    public byte[] getBlockByNumber(long blockNumber) {
        try {
            byte[] blockHash = db.get(hashByNumberHandle, longToBytes(blockNumber));
            if (blockHash != null) {
                return db.get(blocksHandle, blockHash);
            }
            return null;
        } catch (Exception e) {
            log.error("Failed to get block by number", e);
            return null;
        }
    }
    
    @Override
    public byte[] getBlockHeaderByNumber(long blockNumber) {
        try {
            byte[] blockHash = db.get(hashByNumberHandle, longToBytes(blockNumber));
            if (blockHash != null) {
                return db.get(headersHandle, blockHash);
            }
            return null;
        } catch (Exception e) {
            log.error("Failed to get block header by number", e);
            return null;
        }
    }
    
    @Override
    public void rollbackTo(long slot) {
        ChainTip currentTip = getTip();
        log.warn("Rollback requested: to slot={}, current tip={}", slot, 
            currentTip != null ? String.format("slot=%d, block=%d", currentTip.getSlot(), currentTip.getBlockNumber()) : "null");
        
        if (currentTip == null || currentTip.getSlot() <= slot) {
            log.debug("Rollback skipped: no rollback needed");
            return;
        }
        
        try {
            // Find rollback point
            byte[] rollbackBlockNumberBytes = db.get(numberBySlotHandle, longToBytes(slot));
            if (rollbackBlockNumberBytes == null) {
                return;
            }
            
            long rollbackBlockNumber = bytesToLong(rollbackBlockNumberBytes);
            
            // Remove all blocks after rollback point
            try (WriteBatch batch = new WriteBatch()) {
                for (long blockNum = rollbackBlockNumber + 1; blockNum <= currentTip.getBlockNumber(); blockNum++) {
                    byte[] blockHash = db.get(hashByNumberHandle, longToBytes(blockNum));
                    if (blockHash != null) {
                        // Remove block and header
                        batch.delete(blocksHandle, blockHash);
                        batch.delete(headersHandle, blockHash);
                        
                        // Remove mappings
                        batch.delete(hashByNumberHandle, longToBytes(blockNum));
                        
                        byte[] slotBytes = db.get(slotByNumberHandle, longToBytes(blockNum));
                        if (slotBytes != null) {
                            batch.delete(numberBySlotHandle, slotBytes);
                        }
                        batch.delete(slotByNumberHandle, longToBytes(blockNum));
                    }
                }
                
                // Update tip to rollback point
                byte[] rollbackHash = db.get(hashByNumberHandle, longToBytes(rollbackBlockNumber));
                if (rollbackHash != null) {
                    ChainTip newTip = new ChainTip(slot, rollbackHash, rollbackBlockNumber);
                    batch.put(metadataHandle, TIP_KEY, serializeChainTip(newTip));
                }
                
                db.write(new WriteOptions(), batch);
            }
            
            log.warn("Rollback completed: to slot={}, deleted {} blocks", slot, 
                currentTip.getBlockNumber() - rollbackBlockNumber);
        } catch (Exception e) {
            log.error("Rollback failed: to slot={}", slot, e);
            throw new RuntimeException("Failed to rollback", e);
        }
    }
    
    @Override
    public ChainTip getTip() {
        try {
            byte[] tipData = db.get(metadataHandle, TIP_KEY);
            if (tipData != null) {
                return deserializeChainTip(tipData);
            }
            return null;
        } catch (Exception e) {
            log.error("Failed to get tip", e);
            return null;
        }
    }
    
    @Override
    public Point findNextBlock(Point currentPoint) {
        try {
            long currentSlot = currentPoint.getSlot();
            ChainTip tip = getTip();
            
            if (tip == null) {
                log.warn("Chain tip is null, cannot find next block");
                return null;
            }
            
            long tipSlot = tip.getSlot();
            long tipBlockNumber = tip.getBlockNumber();
            
            // If current slot is already at or beyond tip, no next block available
            if (currentSlot >= tipSlot) {
                log.debug("Current slot {} is at or beyond tip slot {}, no next block", currentSlot, tipSlot);
                return null;
            }
            
            // Handle Point.ORIGIN (slot 0) specially
            if (currentSlot == 0 && currentPoint.getHash() == null) {
                // Return the first block
                byte[] firstBlockData = db.get(metadataHandle, FIRST_BLOCK_KEY);
                if (firstBlockData != null) {
                    Point firstBlock = deserializePoint(firstBlockData);
                    log.debug("Returning first block after Point.ORIGIN: {}", firstBlock);
                    return firstBlock;
                }
                // Fall back to block number 0
                byte[] blockHash = db.get(hashByNumberHandle, longToBytes(0));
                if (blockHash != null) {
                    byte[] slotBytes = db.get(slotByNumberHandle, longToBytes(0));
                    if (slotBytes != null) {
                        long slot = bytesToLong(slotBytes);
                        return new Point(slot, HexUtil.encodeHexString(blockHash));
                    }
                }
            }
            
            // Try to find current block number first
            Long currentBlockNumber = null;
            
            // First try to get block number from the current slot
            byte[] blockNumberBytes = db.get(numberBySlotHandle, longToBytes(currentSlot));
            if (blockNumberBytes != null) {
                currentBlockNumber = bytesToLong(blockNumberBytes);
                log.debug("Found current block number {} for slot {}", currentBlockNumber, currentSlot);
            } else {
                // If exact slot doesn't have a block, use iterator to find the nearest block
                log.debug("No block at exact slot {}, searching for nearest block using iterator", currentSlot);
                
                try (RocksIterator iterator = db.newIterator(slotByNumberHandle)) {
                    // Start from block 0 and find the last block before or at currentSlot
                    iterator.seekToFirst();
                    Long lastBlockNumber = null;
                    
                    while (iterator.isValid()) {
                        long blockNumber = bytesToLong(iterator.key());
                        long slot = bytesToLong(iterator.value());
                        
                        if (slot <= currentSlot) {
                            lastBlockNumber = blockNumber;
                        } else {
                            break; // We've passed the current slot
                        }
                        
                        iterator.next();
                    }
                    
                    if (lastBlockNumber != null) {
                        currentBlockNumber = lastBlockNumber;
                        log.debug("Found nearest block number {} before slot {}", currentBlockNumber, currentSlot);
                    }
                }
            }
            
            if (currentBlockNumber == null) {
                log.warn("Could not determine current block number for slot {}", currentSlot);
                return null;
            }
            
            // Now use block number to find the next block efficiently
            long nextBlockNumber = currentBlockNumber + 1;
            
            // Check if next block exists
            if (nextBlockNumber > tipBlockNumber) {
                log.debug("Next block number {} would exceed tip block number {}", nextBlockNumber, tipBlockNumber);
                return null;
            }
            
            // Get the next block directly by block number
            byte[] nextBlockHash = db.get(hashByNumberHandle, longToBytes(nextBlockNumber));
            if (nextBlockHash != null) {
                byte[] nextSlotBytes = db.get(slotByNumberHandle, longToBytes(nextBlockNumber));
                if (nextSlotBytes != null) {
                    long nextSlot = bytesToLong(nextSlotBytes);
                    Point nextBlock = new Point(nextSlot, HexUtil.encodeHexString(nextBlockHash));
                    log.debug("Found next block: number={}, slot={}, hash={}", 
                             nextBlockNumber, nextSlot, nextBlock.getHash());
                    return nextBlock;
                }
            }
            
            log.warn("Could not find next block after block number {}", currentBlockNumber);
            return null;
            
        } catch (Exception e) {
            log.error("Failed to find next block after slot {}", currentPoint.getSlot(), e);
            return null;
        }
    }
    
    @Override
    public List<Point> findBlocksInRange(Point from, Point to) {
        List<Point> blocks = new ArrayList<>();
        
        try {
            long fromSlot = from.getSlot();
            long toSlot = to.getSlot();
            
            for (long slot = fromSlot; slot <= toSlot; slot++) {
                byte[] blockNumberBytes = db.get(numberBySlotHandle, longToBytes(slot));
                if (blockNumberBytes != null) {
                    long blockNumber = bytesToLong(blockNumberBytes);
                    byte[] blockHash = db.get(hashByNumberHandle, longToBytes(blockNumber));
                    if (blockHash != null) {
                        blocks.add(new Point(slot, HexUtil.encodeHexString(blockHash)));
                    }
                }
            }
            
            return blocks;
        } catch (Exception e) {
            log.error("Failed to find blocks in range", e);
            return blocks;
        }
    }
    
    @Override
    public boolean hasPoint(Point point) {
        try {
            byte[] blockNumberBytes = db.get(numberBySlotHandle, longToBytes(point.getSlot()));
            if (blockNumberBytes == null) {
                return false;
            }
            
            long blockNumber = bytesToLong(blockNumberBytes);
            byte[] blockHash = db.get(hashByNumberHandle, longToBytes(blockNumber));
            
            if (blockHash != null && point.getHash() != null) {
                return HexUtil.encodeHexString(blockHash).equals(point.getHash());
            }
            
            return blockHash != null;
        } catch (Exception e) {
            log.error("Failed to check point", e);
            return false;
        }
    }
    
    @Override
    public Long getBlockNumberBySlot(long slot) {
        try {
            byte[] blockNumberBytes = db.get(numberBySlotHandle, longToBytes(slot));
            if (blockNumberBytes != null) {
                return bytesToLong(blockNumberBytes);
            }
            return null;
        } catch (Exception e) {
            log.error("Failed to get block number by slot", e);
            return null;
        }
    }
    
    /**
     * Get the first block in the chain
     */
    public Point getFirstBlock() {
        try {
            byte[] firstBlockData = db.get(metadataHandle, FIRST_BLOCK_KEY);
            if (firstBlockData != null) {
                return deserializePoint(firstBlockData);
            }
            
            // If no first block stored, try to find it
            // Look for block 0 or the lowest slot
            ChainTip tip = getTip();
            if (tip != null) {
                // For now, assume genesis is at slot 0
                byte[] blockNumberBytes = db.get(numberBySlotHandle, longToBytes(0));
                if (blockNumberBytes != null) {
                    long blockNumber = bytesToLong(blockNumberBytes);
                    byte[] blockHash = db.get(hashByNumberHandle, longToBytes(blockNumber));
                    if (blockHash != null) {
                        return new Point(0, HexUtil.encodeHexString(blockHash));
                    }
                }
            }
            
            return null;
        } catch (Exception e) {
            log.error("Failed to get first block", e);
            return null;
        }
    }
    
    /**
     * Close the database connection
     */
    public void close() {
        try {
            blocksHandle.close();
            headersHandle.close();
            hashByNumberHandle.close();
            numberBySlotHandle.close();
            slotByNumberHandle.close();
            metadataHandle.close();
            db.close();
        } catch (Exception e) {
            log.error("Failed to close RocksDB", e);
        }
    }
    
    // Helper methods
    
    private byte[] longToBytes(long value) {
        return ByteBuffer.allocate(Long.BYTES).putLong(value).array();
    }
    
    private long bytesToLong(byte[] bytes) {
        return ByteBuffer.wrap(bytes).getLong();
    }
    
    private byte[] serializeChainTip(ChainTip tip) {
        try {
            ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES * 2 + tip.getBlockHash().length);
            buffer.putLong(tip.getSlot());
            buffer.putLong(tip.getBlockNumber());
            buffer.put(tip.getBlockHash());
            return buffer.array();
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize chain tip", e);
        }
    }
    
    private ChainTip deserializeChainTip(byte[] data) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            long slot = buffer.getLong();
            long blockNumber = buffer.getLong();
            byte[] blockHash = new byte[buffer.remaining()];
            buffer.get(blockHash);
            return new ChainTip(slot, blockHash, blockNumber);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize chain tip", e);
        }
    }
    
    private byte[] serializePoint(Point point) {
        try {
            byte[] hashBytes = point.getHash() != null ? 
                HexUtil.decodeHexString(point.getHash()) : new byte[0];
            
            ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES + Integer.BYTES + hashBytes.length);
            buffer.putLong(point.getSlot());
            buffer.putInt(hashBytes.length);
            if (hashBytes.length > 0) {
                buffer.put(hashBytes);
            }
            return buffer.array();
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize point", e);
        }
    }
    
    private Point deserializePoint(byte[] data) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            long slot = buffer.getLong();
            int hashLength = buffer.getInt();
            
            String hash = null;
            if (hashLength > 0) {
                byte[] hashBytes = new byte[hashLength];
                buffer.get(hashBytes);
                hash = HexUtil.encodeHexString(hashBytes);
            }
            
            return new Point(slot, hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize point", e);
        }
    }
}