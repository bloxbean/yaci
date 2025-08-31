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

                    System.exit(1);
                    // Throw exception to stop sync and prevent gaps
                    throw new IllegalStateException(errorMsg);
                }
                log.debug("✅ Continuity check passed for block #{}", blockNumber);
            } else if (blockNumber == 1) {
                log.info("📍 Storing genesis/first block #{}", blockNumber);
            }

            // HASH CONSISTENCY CHECK: Validate block matches stored header
            byte[] expectedHash = db.get(hashByNumberHandle, longToBytes(blockNumber));
            if (expectedHash != null && !Arrays.equals(expectedHash, blockHash)) {
                log.warn("🚨 FORK MISMATCH: Block #{} at slot {} has different hash than header! " +
                         "Expected: {}, Got: {} - SKIPPING STORAGE TO PREVENT CORRUPTION",
                         blockNumber, slot, 
                         HexUtil.encodeHexString(expectedHash), 
                         HexUtil.encodeHexString(blockHash));
                return; // Skip storing mismatched block to prevent index corruption
            }

            // Use write batch for atomic updates
            try (WriteBatch batch = new WriteBatch()) {
                // Store block
                batch.put(blocksHandle, blockHash, block);

                // IMPORTANT: Do NOT update indices here - only headers should manage indices
                // This prevents body sync from overwriting header mappings during forks
                // updateChainState(batch, blockHash, blockNumber, slot); // REMOVED

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

            // Get current tips to determine rollback strategy
            ChainTip bodyTip = getTip();
            ChainTip headerTip = getHeaderTip();

            // Intelligently decide rollback strategy based on body tip position
            if (bodyTip == null || slot > bodyTip.getSlot()) {
                // Header-only rollback: common during restart when starting from header_tip
                log.info("Header-only rollback to slot {} (body tip at {})",
                         slot, bodyTip != null ? bodyTip.getSlot() : "null");
                performHeaderOnlyRollback(slot, rollbackBlockNumber, rollbackHash, headerTip);
            } else {
                // Full rollback: real chain reorganization affecting both headers and bodies
                log.warn("Full rollback to slot {} (affecting headers and bodies)", slot);
                performFullRollback(slot, rollbackBlockNumber, rollbackHash, bodyTip, headerTip);
            }

        } catch (Exception e) {
            log.error("Rollback failed: to slot={}", slot, e);
            throw new RuntimeException("Failed to rollback to slot " + slot, e);
        }
    }

    /**
     * Perform a header-only rollback - efficient for restart scenarios
     * This is used when the rollback point is beyond the current body tip,
     * typically during restart when we start from header_tip.
     */
    private void performHeaderOnlyRollback(Long slot, long rollbackBlockNumber, byte[] rollbackHash, ChainTip headerTip) throws RocksDBException {
        if (headerTip == null || headerTip.getSlot() <= slot) {
            log.debug("No header rollback needed - header tip at or before rollback point");
            return;
        }

        WriteBatch batch = new WriteBatch();
        int headersDeleted = 0;

        try {
            // Delete headers after the rollback slot
            try (RocksIterator iterator = db.newIterator(numberBySlotHandle)) {
                iterator.seekToLast();

                while (iterator.isValid()) {
                    long currentSlot = bytesToLong(iterator.key());

                    // Stop when we reach the rollback point
                    if (currentSlot <= slot) {
                        break;
                    }

                    long blockNumber = bytesToLong(iterator.value());
                    byte[] blockHash = db.get(hashByNumberHandle, longToBytes(blockNumber));

                    if (blockHash != null) {
                        // Only delete header data (not checking for bodies as this is header-only)
                        byte[] headerData = db.get(headersHandle, blockHash);
                        if (headerData != null) {
                            batch.delete(headersHandle, blockHash);
                            headersDeleted++;
                        }

                        // Delete mappings
                        batch.delete(hashByNumberHandle, longToBytes(blockNumber));
                        batch.delete(numberBySlotHandle, iterator.key());
                        batch.delete(slotByNumberHandle, longToBytes(blockNumber));
                    }

                    iterator.prev();
                }
            }

            // Update header_tip to rollback point
            ChainTip newHeaderTip = new ChainTip(slot, rollbackHash, rollbackBlockNumber);
            batch.put(metadataHandle, HEADER_TIP_KEY, serializeChainTip(newHeaderTip));

            // Commit all changes
            db.write(new WriteOptions(), batch);

            log.info("Header-only rollback completed: deleted {} headers, new header_tip at slot {}",
                     headersDeleted, slot);

        } finally {
            batch.close();
        }
    }

    /**
     * Perform a full rollback of both headers and bodies - for real chain reorganizations
     * This is the traditional rollback used when the network has a real chain reorg.
     */
    private void performFullRollback(Long slot, long rollbackBlockNumber, byte[] rollbackHash,
                                    ChainTip bodyTip, ChainTip headerTip) throws RocksDBException {

        // Determine the highest block number to clean up
        long maxBlockToDelete = Math.max(
            bodyTip != null ? bodyTip.getBlockNumber() : 0,
            headerTip != null ? headerTip.getBlockNumber() : 0
        );

        if (maxBlockToDelete <= rollbackBlockNumber) {
            log.info("No rollback needed - tips are at or before rollback point");
            
            // CHECK TIP ALIGNMENT: Ensure header and body tips have same hash
            if (headerTip != null && bodyTip != null && 
                !Arrays.equals(headerTip.getBlockHash(), bodyTip.getBlockHash())) {
                
                log.warn("🚨 TIP MISMATCH DETECTED: Header tip and body tip have different hashes!");
                log.warn("Header tip: block #{} slot {} hash {}", 
                         headerTip.getBlockNumber(), headerTip.getSlot(), 
                         HexUtil.encodeHexString(headerTip.getBlockHash()));
                log.warn("Body tip: block #{} slot {} hash {}", 
                         bodyTip.getBlockNumber(), bodyTip.getSlot(), 
                         HexUtil.encodeHexString(bodyTip.getBlockHash()));
                
                // Find the last block where header and body hashes match
                long alignedBlockNumber = findLastAlignedBlock(rollbackBlockNumber);
                if (alignedBlockNumber > 0) {
                    // Get the aligned block's details
                    byte[] alignedHash = db.get(hashByNumberHandle, longToBytes(alignedBlockNumber));
                    byte[] slotBytes = db.get(slotByNumberHandle, longToBytes(alignedBlockNumber));
                    
                    if (alignedHash != null && slotBytes != null) {
                        long alignedSlot = bytesToLong(slotBytes);
                        
                        // Update body tip to the aligned point
                        ChainTip alignedTip = new ChainTip(alignedSlot, alignedHash, alignedBlockNumber);
                        WriteBatch batch = new WriteBatch();
                        try {
                            batch.put(metadataHandle, TIP_KEY, serializeChainTip(alignedTip));
                            db.write(new WriteOptions(), batch);
                            
                            log.warn("✅ REALIGNED body tip to block #{} at slot {} where header/body hashes match",
                                    alignedBlockNumber, alignedSlot);
                        } finally {
                            batch.close();
                        }
                    }
                } else {
                    log.error("Could not find aligned block - manual intervention may be required");
                }
            }
            
            return;
        }

        WriteBatch batch = new WriteBatch();
        int blocksDeleted = 0;
        int headersDeleted = 0;
        int slotsDeleted = 0;

        try {
            // Delete all slots, blocks and headers after the rollback point
            try (RocksIterator iterator = db.newIterator(numberBySlotHandle)) {
                iterator.seekToLast();

                while (iterator.isValid()) {
                    long currentSlot = bytesToLong(iterator.key());

                    // Stop when we reach the rollback point
                    if (currentSlot <= slot) {
                        break;
                    }

                    long blockNumber = bytesToLong(iterator.value());
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
                            headersDeleted++;
                        }

                        // Delete mappings
                        batch.delete(hashByNumberHandle, longToBytes(blockNumber));
                        batch.delete(numberBySlotHandle, iterator.key());
                        batch.delete(slotByNumberHandle, longToBytes(blockNumber));
                    }

                    slotsDeleted++;
                    iterator.prev();
                }
            }

            // Update both header_tip and body_tip to rollback point
            ChainTip newTip = new ChainTip(slot, rollbackHash, rollbackBlockNumber);
            batch.put(metadataHandle, HEADER_TIP_KEY, serializeChainTip(newTip));
            batch.put(metadataHandle, TIP_KEY, serializeChainTip(newTip));

            // Commit all changes
            db.write(new WriteOptions(), batch);

            log.warn("Full rollback completed: to slot={}, deleted {} slots, {} blocks, {} headers",
                     slot, slotsDeleted, blocksDeleted, headersDeleted);

        } finally {
            batch.close();
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

            // First, determine the current block number from the slot
            Long currentBlockNumber = getBlockNumberBySlot(currentSlot);
            if (currentBlockNumber == null) {
                // If exact slot doesn't have a block, find the nearest previous block
                log.debug("No block at exact slot {}, searching for nearest previous block", currentSlot);
                try (RocksIterator iterator = db.newIterator(numberBySlotHandle)) {
                    iterator.seekForPrev(longToBytes(currentSlot));
                    if (iterator.isValid()) {
                        currentBlockNumber = bytesToLong(iterator.value());
                        long foundSlot = bytesToLong(iterator.key());
                        log.debug("Found nearest block number {} at previous slot {}", currentBlockNumber, foundSlot);
                    }
                }
            }

            if (currentBlockNumber == null) {
                log.warn("Cannot determine current block number for slot {}", currentSlot);
                return null;
            }

            // Look for the next sequential block number
            long nextBlockNumber = currentBlockNumber + 1;

            // Check if the next sequential block exists
            byte[] nextBlockHash = db.get(hashByNumberHandle, longToBytes(nextBlockNumber));
            if (nextBlockHash == null) {
                log.info("Next sequential block #{} not found in headers yet", nextBlockNumber);
                return null;
            }

            // Get the slot for this block
            byte[] slotBytes = db.get(slotByNumberHandle, longToBytes(nextBlockNumber));
            if (slotBytes == null) {
                log.warn("Block #{} exists but slot mapping is missing", nextBlockNumber);
                return null;
            }

            long nextSlot = bytesToLong(slotBytes);
            Point nextHeader = new Point(nextSlot, HexUtil.encodeHexString(nextBlockHash));

            log.debug("Found next sequential header: block #{} at slot {} with hash {}",
                     nextBlockNumber, nextSlot, nextHeader.getHash());
            return nextHeader;

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
        if (log.isDebugEnabled())
            log.debug("🔍 findLastPointAfterNBlocks called: from={}, batchSize={}", from, batchSize);

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

            if (log.isDebugEnabled())
                log.debug("🔍 After iteration: counter={}, lastBlockNumber={}, lastSlot={}", counter, lastBlockNumber, lastSlot);

            byte[] blockHash = db.get(hashByNumberHandle, longToBytes(lastBlockNumber));
            if (blockHash == null) {
                log.info("❌ findLastPointAfterNBlocks returning NULL - no hash for block {}", lastBlockNumber);
                return null;
            }

            Point result = new Point(lastSlot, HexUtil.encodeHexString(blockHash));

            if (log.isDebugEnabled())
                log.debug("✅ findLastPointAfterNBlocks returning: {}", result);
            return result;
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
            // Find the first block/header dynamically from indices
            // This works for both headers and blocks since both update the indices

            // Start from block number 1 (Byron mainnet has block 1 at slot 0)
            // We check sequentially to find the minimum block number that exists
            for (long blockNum = 1; blockNum <= 100; blockNum++) {
                byte[] slotBytes = db.get(slotByNumberHandle, longToBytes(blockNum));
                if (slotBytes != null) {
                    // Found the first block number
                    long slot = bytesToLong(slotBytes);
                    byte[] blockHash = db.get(hashByNumberHandle, longToBytes(blockNum));
                    if (blockHash != null) {
                        log.debug("Found first block/header at blockNumber={}, slot={}", blockNum, slot);
                        return new Point(slot, HexUtil.encodeHexString(blockHash));
                    }
                }
            }

            // If we still haven't found anything, check if there's a header tip
            // This might happen if headers are synced but no mapping exists yet
            ChainTip headerTip = getHeaderTip();
            if (headerTip != null) {
                log.warn("No first block found in indices, but header tip exists. Chain state might be incomplete.");
            }

            return null;
        } catch (Exception e) {
            log.error("Failed to get first block", e);
            return null;
        }
    }

    /**
     * Recover from corrupted chain state by finding the last valid continuous point
     * and removing all data after that point.
     *
     * This method:
     * 1. Scans backwards from current tips to find last continuous block/header
     * 2. Removes all data after that point
     * 3. Updates tips to the recovered position
     */
    public void recoverFromCorruption() {
        log.warn("🔧 Starting chain state recovery from corruption...");

        try {
            ChainTip currentHeaderTip = getHeaderTip();
            ChainTip currentBodyTip = getTip();

            if (currentHeaderTip == null && currentBodyTip == null) {
                log.info("✅ Chain state is empty, no recovery needed");
                return;
            }

            // Find the last continuous header sequence
            Long lastValidHeaderBlock = findLastContinuousHeaderBlock();
            Long lastValidBodyBlock = findLastContinuousBodyBlock();

            log.info("🔍 Recovery analysis: Last valid header block: {}, Last valid body block: {}",
                    lastValidHeaderBlock, lastValidBodyBlock);

            // Determine recovery point - use the lower of the two
            Long recoveryBlockNumber = null;
            if (lastValidHeaderBlock != null && lastValidBodyBlock != null) {
                recoveryBlockNumber = Math.min(lastValidHeaderBlock, lastValidBodyBlock);
            } else if (lastValidHeaderBlock != null) {
                recoveryBlockNumber = lastValidHeaderBlock;
            } else if (lastValidBodyBlock != null) {
                recoveryBlockNumber = lastValidBodyBlock;
            }

            if (recoveryBlockNumber == null || recoveryBlockNumber <= 0) {
                log.error("❌ Cannot find any valid continuous data. Manual intervention required.");
                return;
            }

            // Get the slot for the recovery point
            byte[] recoverySlotBytes = db.get(slotByNumberHandle, longToBytes(recoveryBlockNumber));
            if (recoverySlotBytes == null) {
                log.error("❌ Cannot find slot for recovery block {}. Manual intervention required.", recoveryBlockNumber);
                return;
            }

            long recoverySlot = bytesToLong(recoverySlotBytes);

            log.warn("🔧 RECOVERY: Rolling back to block #{} at slot {} to restore continuity",
                    recoveryBlockNumber, recoverySlot);

            // Use the existing rollback mechanism to clean up everything after the recovery point
            rollbackTo(recoverySlot);

            log.info("✅ Chain state recovery completed successfully at block #{}, slot {}",
                    recoveryBlockNumber, recoverySlot);

        } catch (Exception e) {
            log.error("❌ Chain state recovery failed", e);
            throw new RuntimeException("Recovery from corruption failed", e);
        }
    }

    /**
     * Quick corruption detection - checks for gaps near current tips
     * More efficient than full scan, suitable for startup checks
     */
    public boolean detectCorruption() {
        try {
            ChainTip headerTip = getHeaderTip();
            ChainTip bodyTip = getTip();

            // No tips means empty state (not corrupted)
            if (headerTip == null && bodyTip == null) {
                return false;
            }

            // Check continuity in a window around current tips
            long maxBlock = 0;
            if (headerTip != null) {
                maxBlock = Math.max(maxBlock, headerTip.getBlockNumber());
            }
            if (bodyTip != null) {
                maxBlock = Math.max(maxBlock, bodyTip.getBlockNumber());
            }

            // Check last 1000 blocks for gaps
            long startBlock = Math.max(1, maxBlock - 1000);

            for (long blockNum = startBlock; blockNum <= maxBlock; blockNum++) {
                byte[] blockHash = db.get(hashByNumberHandle, longToBytes(blockNum));
                if (blockHash == null) {
                    log.warn("🚨 Corruption detected: Missing block #{} in recent range", blockNum);
                    return true;
                }
            }

            return false; // No corruption found in recent range

        } catch (Exception e) {
            log.warn("Error during corruption detection", e);
            return false; // Assume not corrupted if we can't check
        }
    }

    /**
     * Find the last block where header and body have matching hashes
     */
    private long findLastAlignedBlock(long maxBlockNumber) throws RocksDBException {
        log.info("🔍 Searching for last aligned block where header and body hashes match...");
        
        // Search backwards from maxBlockNumber to find alignment
        for (long blockNum = maxBlockNumber; blockNum > 0; blockNum--) {
            byte[] blockHash = db.get(hashByNumberHandle, longToBytes(blockNum));
            if (blockHash == null) {
                continue; // Skip missing blocks
            }
            
            // Check if both header and body exist for this hash
            byte[] header = db.get(headersHandle, blockHash);
            byte[] body = db.get(blocksHandle, blockHash);
            
            if (header != null && body != null) {
                log.info("✅ Found aligned block at #{} with matching header and body", blockNum);
                return blockNum;
            }
            
            // Log progress every 1000 blocks
            if ((maxBlockNumber - blockNum) % 1000 == 0 && blockNum != maxBlockNumber) {
                log.debug("Alignment search: checked {} blocks, currently at block #{}",
                         maxBlockNumber - blockNum, blockNum);
            }
        }
        
        log.warn("Could not find aligned block in range 1 to {}", maxBlockNumber);
        return 0; // No aligned block found
    }

    /**
     * Find the last block number where headers form a continuous sequence
     */
    private Long findLastContinuousHeaderBlock() throws RocksDBException {
        log.info("🔍 Scanning for last continuous header sequence...");

        // Start from block 1 and scan forward to find the first gap
        for (long blockNum = 1; blockNum < 10_000_000; blockNum++) {
            byte[] headerHash = db.get(hashByNumberHandle, longToBytes(blockNum));
            if (headerHash == null) {
                long lastValid = blockNum - 1;
                log.info("📄 Last continuous header found at block #{} (gap at block #{})", lastValid, blockNum);
                return lastValid > 0 ? lastValid : null;
            }

            // Check if header actually exists
            byte[] headerData = db.get(headersHandle, headerHash);
            if (headerData == null) {
                long lastValid = blockNum - 1;
                log.info("📄 Last continuous header found at block #{} (missing header data at block #{})", lastValid, blockNum);
                return lastValid > 0 ? lastValid : null;
            }

            // Progress logging every 100K blocks
            if (blockNum % 100_000 == 0) {
                log.info("📄 Header continuity check: processed up to block #{}", blockNum);
            }
        }

        log.warn("📄 Header scan reached maximum without finding gap (this shouldn't happen)");
        return null;
    }

    /**
     * Find the last block number where bodies form a continuous sequence
     */
    private Long findLastContinuousBodyBlock() throws RocksDBException {
        log.info("🔍 Scanning for last continuous body sequence...");

        // Start from block 1 and scan forward to find the first gap
        for (long blockNum = 1; blockNum < 10_000_000; blockNum++) {
            byte[] blockHash = db.get(hashByNumberHandle, longToBytes(blockNum));
            if (blockHash == null) {
                long lastValid = blockNum - 1;
                log.info("🧱 Last continuous body found at block #{} (gap at block #{})", lastValid, blockNum);
                return lastValid > 0 ? lastValid : null;
            }

            // Check if body actually exists
            byte[] bodyData = db.get(blocksHandle, blockHash);
            if (bodyData == null) {
                long lastValid = blockNum - 1;
                log.info("🧱 Last continuous body found at block #{} (missing body data at block #{})", lastValid, blockNum);
                return lastValid > 0 ? lastValid : null;
            }

            // Progress logging every 100K blocks
            if (blockNum % 100_000 == 0) {
                log.info("🧱 Body continuity check: processed up to block #{}", blockNum);
            }
        }

        log.warn("🧱 Body scan reached maximum without finding gap (this shouldn't happen)");
        return null;
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
        // Update tip if this is a newer block OR same slot with higher block number (fork handling)
        ChainTip currentTip = getTip();
        if (currentTip == null || slot > currentTip.getSlot() || 
            (slot.equals(currentTip.getSlot()) && blockNumber > currentTip.getBlockNumber())) {
            ChainTip newTip = new ChainTip(slot, blockHash, blockNumber);
            batch.put(metadataHandle, TIP_KEY, serializeChainTip(newTip));
            log.debug("Updated tip: slot={}, blockNumber={} (fork handling: same-slot={})", 
                     slot, blockNumber, currentTip != null && slot.equals(currentTip.getSlot()));
        } else if (currentTip != null && slot.equals(currentTip.getSlot()) && blockNumber.equals(currentTip.getBlockNumber())) {
            // Same slot, same block number but potentially different hash (fork scenario)
            if (!Arrays.equals(blockHash, currentTip.getBlockHash())) {
                log.warn("⚠️ FORK DETECTED: Same slot {} and block #{} but different hash! Current: {}, New: {}",
                        slot, blockNumber, 
                        HexUtil.encodeHexString(currentTip.getBlockHash()),
                        HexUtil.encodeHexString(blockHash));
                // In this case, we should update to the new hash as it represents the canonical chain
                ChainTip newTip = new ChainTip(slot, blockHash, blockNumber);
                batch.put(metadataHandle, TIP_KEY, serializeChainTip(newTip));
                log.info("Updated tip to new fork: slot={}, blockNumber={}", slot, blockNumber);
            }
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

}
