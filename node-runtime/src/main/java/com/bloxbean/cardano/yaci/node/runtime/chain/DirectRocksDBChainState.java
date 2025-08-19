package com.bloxbean.cardano.yaci.node.runtime.chain;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
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
public class DirectRocksDBChainState implements ChainState, AutoCloseable {

    private static final byte[] TIP_KEY = "tip".getBytes(StandardCharsets.UTF_8);
    private static final byte[] HEADER_TIP_KEY = "header_tip".getBytes(StandardCharsets.UTF_8);
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
    public void storeBlock(byte[] blockHash, Long blockNumber, Long slot, byte[] block) {
        try {
            // MANDATORY CONTINUITY CHECK: Prevent gaps in chainstate
            if (blockNumber > 1) {
                byte[] previousBlock = getBlockByNumber(blockNumber - 1);
                if (previousBlock == null) {
                    String errorMsg = String.format(
                        "🚨 CONTINUITY VIOLATION: Cannot store block #%d - previous block #%d is missing! " +
                        "This would create gaps in chainstate. slot=%d, hash=%s",
                        blockNumber, blockNumber - 1, slot, HexUtil.encodeHexString(blockHash));
                    log.error(errorMsg);
                    
                    // Throw exception to stop sync and prevent gaps
                    throw new IllegalStateException(errorMsg);
                }
                log.debug("✅ Continuity check passed for block #{}", blockNumber);
            } else if (blockNumber == 1) {
                log.info("📍 Storing genesis/first block #{}", blockNumber);
            }

            // Use write batch for atomic updates
            try (WriteBatch batch = new WriteBatch()) {
                // Store block
                batch.put(blocksHandle, blockHash, block);

                // Update indexes
                updateChainState(batch, blockHash, blockNumber, slot);

                // Update tip if this is a newer block
                updateTip(batch, blockHash, blockNumber, slot);

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
    public void storeBlockHeader(byte[] blockHash, Long blockNumber, Long slot, byte[] blockHeader) {
        try {
            // MANDATORY CONTINUITY CHECK: Prevent gaps in header chainstate
            if (blockNumber != null && blockNumber > 1) {
                byte[] previousHeader = getBlockHeaderByNumber(blockNumber - 1);
                if (previousHeader == null) {
                    String errorMsg = String.format(
                        "🚨 HEADER CONTINUITY VIOLATION: Cannot store header #%d - previous header #%d is missing! " +
                        "This would create gaps in header chainstate. slot=%d, hash=%s",
                        blockNumber, blockNumber - 1, slot, HexUtil.encodeHexString(blockHash));
                    log.error(errorMsg);
                    
                    // Throw exception to stop sync and prevent gaps
                    throw new IllegalStateException(errorMsg);
                }
                log.debug("✅ Header continuity check passed for header #{}", blockNumber);
            } else if (blockNumber != null && blockNumber == 1) {
                log.info("📍 Storing genesis/first header #{}", blockNumber);
            }

            // Use write batch for atomic updates
            try (WriteBatch batch = new WriteBatch()) {
                // Store header
                batch.put(headersHandle, blockHash, blockHeader);
                ChainTip newHeaderTip = new ChainTip(slot, blockHash, blockNumber);
                batch.put(metadataHandle, HEADER_TIP_KEY, serializeChainTip(newHeaderTip));

                // If we successfully extracted slot and block number, update indices
                if (slot != null && blockNumber != null) {
                    updateChainState(batch, blockHash, blockNumber, slot);
                    if (log.isDebugEnabled()) {
                        log.debug("Updated Metadata: slot={}, blockNumber={}", slot, blockNumber);
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
    public byte[] getBlockByNumber(Long blockNumber) {
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
    public byte[] getBlockHeaderByNumber(Long blockNumber) {
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
    public void rollbackTo(Long slot) {
        try {
            // Check if the exact requested slot exists
            byte[] blockNumberBytes = db.get(numberBySlotHandle, longToBytes(slot));
            if (blockNumberBytes == null) {
                log.error("Rollback failed: requested slot {} does not exist in storage", slot);
                throw new RuntimeException("Cannot rollback to slot " + slot + " - slot not found in storage");
            }

            long rollbackBlockNumber = bytesToLong(blockNumberBytes);
            byte[] rollbackHash = db.get(hashByNumberHandle, longToBytes(rollbackBlockNumber));

            if (rollbackHash == null) {
                log.error("Rollback failed: block hash not found for slot {} block {}", slot, rollbackBlockNumber);
                throw new RuntimeException("Cannot rollback to slot " + slot + " - block hash not found");
            }

            ChainTip currentTip = getTip();
            ChainTip headerTip = getHeaderTip();
            log.warn("Rollback requested: to slot={}, block={}", slot, rollbackBlockNumber);

            // Determine the highest block number to clean up
            long maxBlockToDelete = currentTip != null ? currentTip.getBlockNumber() : 0;
            if (headerTip != null && headerTip.getBlockNumber() > maxBlockToDelete) {
                maxBlockToDelete = headerTip.getBlockNumber();
                log.info("Rollback will also clean up orphaned headers up to block {}", maxBlockToDelete);
            }

            if (maxBlockToDelete <= rollbackBlockNumber) {
                log.debug("Rollback skipped: no rollback needed. Current tips are at or before the rollback point.");
                return;
            }

            // Remove all blocks and headers after rollback point
            try (WriteBatch batch = new WriteBatch()) {
                int blocksDeleted = 0;
                int headersOnlyDeleted = 0;
                int slotsDeleted = 0;
                
                // FIRST PASS: Delete all slots after the rollback slot
                // This catches orphaned slots with non-sequential block numbers
                log.info("Starting slot-based cleanup for rollback to slot {}", slot);
                try (RocksIterator iterator = db.newIterator(numberBySlotHandle)) {
                    iterator.seek(longToBytes(slot + 1)); // Start from slot after rollback point
                    
                    while (iterator.isValid()) {
                        long currentSlot = bytesToLong(iterator.key());
                        long blockNumber = bytesToLong(iterator.value());
                        
                        if (log.isDebugEnabled()) {
                            log.debug("Deleting slot {} (block {}) during rollback", currentSlot, blockNumber);
                        }
                        
                        // Get block hash for this block number
                        byte[] blockHash = db.get(hashByNumberHandle, longToBytes(blockNumber));
                        
                        if (blockHash != null) {
                            // Delete block and header data
                            byte[] blockBody = db.get(blocksHandle, blockHash);
                            byte[] headerData = db.get(headersHandle, blockHash);
                            
                            if (blockBody != null) {
                                batch.delete(blocksHandle, blockHash);
                                blocksDeleted++;
                            }
                            if (headerData != null) {
                                batch.delete(headersHandle, blockHash);
                                if (blockBody == null) {
                                    headersOnlyDeleted++;
                                }
                            }
                            
                            // Delete hash mapping
                            batch.delete(hashByNumberHandle, longToBytes(blockNumber));
                        }
                        
                        // Delete slot mappings
                        batch.delete(numberBySlotHandle, iterator.key()); // Delete slot->blockNumber
                        batch.delete(slotByNumberHandle, longToBytes(blockNumber)); // Delete blockNumber->slot
                        slotsDeleted++;
                        
                        iterator.next();
                    }
                }
                log.info("Slot-based cleanup deleted {} slots", slotsDeleted);
                
                // SECOND PASS: Delete any remaining blocks by block number
                // This is a safety net for any blocks that might have been missed
                for (long blockNum = rollbackBlockNumber + 1; blockNum <= maxBlockToDelete; blockNum++) {
                    byte[] blockHash = db.get(hashByNumberHandle, longToBytes(blockNum));
                    if (blockHash != null) {
                        // Check if this hasn't been deleted already
                        byte[] blockBody = db.get(blocksHandle, blockHash);
                        byte[] headerData = db.get(headersHandle, blockHash);
                        
                        if (blockBody != null) {
                            batch.delete(blocksHandle, blockHash);
                            blocksDeleted++;
                        }
                        if (headerData != null) {
                            batch.delete(headersHandle, blockHash);
                            if (blockBody == null) {
                                headersOnlyDeleted++;
                            }
                        }

                        // Remove mappings (may already be deleted, but safe to re-delete)
                        batch.delete(hashByNumberHandle, longToBytes(blockNum));

                        byte[] slotBytes = db.get(slotByNumberHandle, longToBytes(blockNum));
                        if (slotBytes != null) {
                            batch.delete(numberBySlotHandle, slotBytes);
                        }
                        batch.delete(slotByNumberHandle, longToBytes(blockNum));
                    }
                }

                // Update tips to rollback point
                // For header tip, we can use the rollback point directly
                ChainTip newHeaderTip = new ChainTip(slot, rollbackHash, rollbackBlockNumber);
                batch.put(metadataHandle, HEADER_TIP_KEY, serializeChainTip(newHeaderTip));
                
                // For body tip, we need to ensure the block body actually exists
                // If rolling back to a header-only point, find the last block with a body
                byte[] blockBody = db.get(blocksHandle, rollbackHash);
                if (blockBody != null) {
                    // The rollback point has a body, safe to use as tip
                    batch.put(metadataHandle, TIP_KEY, serializeChainTip(newHeaderTip));
                } else {
                    // The rollback point is header-only, need to find last block with body
                    log.warn("Rollback point at slot {} is header-only, finding last block with body", slot);
                    
                    // Search backwards for the last block that has a body
                    Long lastBlockWithBody = null;
                    for (long blockNum = rollbackBlockNumber; blockNum > 0; blockNum--) {
                        byte[] hash = db.get(hashByNumberHandle, longToBytes(blockNum));
                        if (hash != null) {
                            byte[] body = db.get(blocksHandle, hash);
                            if (body != null) {
                                lastBlockWithBody = blockNum;
                                byte[] slotBytes = db.get(slotByNumberHandle, longToBytes(blockNum));
                                if (slotBytes != null) {
                                    long bodySlot = bytesToLong(slotBytes);
                                    ChainTip bodyTip = new ChainTip(bodySlot, hash, blockNum);
                                    batch.put(metadataHandle, TIP_KEY, serializeChainTip(bodyTip));
                                    log.info("Set body tip to last block with body: slot={}, block={}", bodySlot, blockNum);
                                }
                                break;
                            }
                        }
                    }
                    
                    if (lastBlockWithBody == null) {
                        log.error("CRITICAL: No blocks with bodies found during rollback!");
                        // Set to null or genesis as a last resort
                        batch.delete(metadataHandle, TIP_KEY);
                    }
                }

                db.write(new WriteOptions(), batch);
                
                log.warn("Rollback completed: to slot={}, new tip at block={}, deleted {} slots, {} blocks and {} header-only entries",
                        slot, rollbackBlockNumber, slotsDeleted, blocksDeleted, headersOnlyDeleted);
            }

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
    public ChainTip getHeaderTip() {
        try {
            byte[] tipData = db.get(metadataHandle, HEADER_TIP_KEY);
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
                Point firstBlock = getFirstBlock();
                if (firstBlock != null) {
                    log.debug("Returning first block after Point.ORIGIN: {}", firstBlock);
                    return firstBlock;
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
                // If exact slot doesn't have a block, find the block at the nearest previous slot
                log.debug("No block at exact slot {}, searching for nearest previous block using iterator", currentSlot);
                try (RocksIterator iterator = db.newIterator(numberBySlotHandle)) {
                    iterator.seekForPrev(longToBytes(currentSlot)); // Seek to the last key <= currentSlot
                    if (iterator.isValid()) {
                        // The key is the slot, and the value is the block number
                        currentBlockNumber = bytesToLong(iterator.value());
                        long foundSlot = bytesToLong(iterator.key());
                        log.debug("Found nearest block number {} at previous slot {}", currentBlockNumber, foundSlot);
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
            if (log.isDebugEnabled()) {
                log.debug("Looking for next block number {} after current block {}", nextBlockNumber, currentBlockNumber);
            }
            byte[] nextBlockHash = db.get(hashByNumberHandle, longToBytes(nextBlockNumber));
            if (log.isDebugEnabled()) {
                log.debug("Hash lookup for block {} result: {}", nextBlockNumber,
                        nextBlockHash != null ? "FOUND (" + HexUtil.encodeHexString(nextBlockHash) + ")" : "NULL");
            }

            if (nextBlockHash != null) {
                byte[] nextSlotBytes = db.get(slotByNumberHandle, longToBytes(nextBlockNumber));
                if (log.isDebugEnabled()) {
                    log.debug("Slot lookup for block {} result: {}", nextBlockNumber,
                            nextSlotBytes != null ? "FOUND (" + bytesToLong(nextSlotBytes) + ")" : "NULL");
                }

                if (nextSlotBytes != null) {
                    long nextSlot = bytesToLong(nextSlotBytes);
                    Point nextBlock = new Point(nextSlot, HexUtil.encodeHexString(nextBlockHash));
                    log.debug("Found next block: number={}, slot={}, hash={}",
                            nextBlockNumber, nextSlot, nextBlock.getHash());
                    return nextBlock;
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("Found hash for block {} but missing slot mapping", nextBlockNumber);
                    }
                }
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("Missing hash mapping for block {}", nextBlockNumber);
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
    public Point findNextBlockHeader(Point currentPoint) {
        try {
            long currentSlot = currentPoint.getSlot();
            ChainTip headerTip = getHeaderTip();

            if (headerTip == null) {
                log.warn("Header tip is null, cannot find next block header");
                return null;
            }

            long headerTipSlot = headerTip.getSlot();

            // If current slot is already at or beyond header tip, no next header available
            if (currentSlot >= headerTipSlot) {
                log.debug("Current slot {} is at or beyond header tip slot {}, no next header", currentSlot, headerTipSlot);
                return null;
            }

            // Use the numberBySlotHandle iterator to find the next slot with a header after currentSlot
            try (RocksIterator iterator = db.newIterator(numberBySlotHandle)) {
                // Seek to the position just after currentSlot
                iterator.seek(longToBytes(currentSlot + 1));

                if (iterator.isValid()) {
                    // Found a slot with a block/header
                    long nextSlot = bytesToLong(iterator.key());
                    long nextBlockNumber = bytesToLong(iterator.value());

                    // Get the hash for this block number (check headers first, then blocks)
                    byte[] nextBlockHash = db.get(hashByNumberHandle, longToBytes(nextBlockNumber));

                    if (nextBlockHash != null) {
                        Point nextHeader = new Point(nextSlot, HexUtil.encodeHexString(nextBlockHash));
                        log.debug("Found next header after slot {}: block #{} at slot {} with hash {}",
                                currentSlot, nextBlockNumber, nextSlot, nextHeader.getHash());
                        return nextHeader;
                    } else {
                        log.warn("Found slot {} with block number {} but missing hash", nextSlot, nextBlockNumber);
                    }
                } else {
                    log.debug("No headers found after slot {} up to header tip at slot {}", currentSlot, headerTipSlot);
                }
            }

            return null;

        } catch (Exception e) {
            log.error("Failed to find next block header after slot {}", currentPoint.getSlot(), e);
            return null;
        }
    }

    @Override
    public List<Point> findBlocksInRange(Point from, Point to) {
        List<Point> blocks = new ArrayList<>();
        try (RocksIterator iterator = db.newIterator(numberBySlotHandle)) {
            long fromSlot = from.getSlot();
            long toSlot = to.getSlot();

            iterator.seek(longToBytes(fromSlot));

            while (iterator.isValid()) {
                long currentSlot = bytesToLong(iterator.key());
                if (currentSlot > toSlot) {
                    break; // We've passed the range
                }

                long blockNumber = bytesToLong(iterator.value());
                byte[] blockHash = db.get(hashByNumberHandle, longToBytes(blockNumber));
                if (blockHash != null) {
                    blocks.add(new Point(currentSlot, HexUtil.encodeHexString(blockHash)));
                }

                iterator.next();
            }
            return blocks;
        } catch (Exception e) {
            log.error("Failed to find blocks in range", e);
            return blocks; // Return what we have so far
        }
    }


    @Override
    public Point findLastPointAfterNBlocks(Point from, long batchSize) {

        long lastBlockNumber = 0;
        long lastSlot = 0;
        try (RocksIterator iterator = db.newIterator(numberBySlotHandle)) {
            long fromSlot = from.getSlot();

            iterator.seek(longToBytes(fromSlot));

            int counter = 0;
            while (counter < batchSize && iterator.isValid()) {
                lastSlot = bytesToLong(iterator.key());
                lastBlockNumber = bytesToLong(iterator.value());

                iterator.next();
                counter ++;
            }

            byte[] blockHash = db.get(hashByNumberHandle, longToBytes(lastBlockNumber));
            if (blockHash == null)
                return null;

            return new Point(lastSlot, HexUtil.encodeHexString(blockHash));
        } catch (Exception e) {
            log.error("Failed to find last point after n blocks", e);
            return null;
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
    public Long getBlockNumberBySlot(Long slot) {
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

    private void updateChainState(WriteBatch batch, byte[] blockHash, Long blockNumber, Long slot) throws RocksDBException {
        // Store mappings
        batch.put(hashByNumberHandle, longToBytes(blockNumber), blockHash);
        batch.put(numberBySlotHandle, longToBytes(slot), longToBytes(blockNumber));
        batch.put(slotByNumberHandle, longToBytes(blockNumber), longToBytes(slot));
    }

    private void updateTip(WriteBatch batch, byte[] blockHash, Long blockNumber, Long slot) throws RocksDBException {
        // Update tip if this is a newer block
        ChainTip currentTip = getTip();
        if (currentTip == null || slot > currentTip.getSlot()) {
            ChainTip newTip = new ChainTip(slot, blockHash, blockNumber);
            batch.put(metadataHandle, TIP_KEY, serializeChainTip(newTip));
            log.debug("Updated tip: slot={}, blockNumber={}", slot, blockNumber);
        }

        // Update first block if needed
        Point firstBlock = getFirstBlock();
        if (firstBlock == null || slot < firstBlock.getSlot()) {
            batch.put(metadataHandle, FIRST_BLOCK_KEY,
                    serializePoint(new Point(slot, HexUtil.encodeHexString(blockHash))));
        }
    }

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
