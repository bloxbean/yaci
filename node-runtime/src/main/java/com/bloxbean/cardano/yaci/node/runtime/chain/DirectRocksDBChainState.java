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
    private final ColumnFamilyHandle numberBySlotHandle;
    private final ColumnFamilyHandle slotByNumberHandle;
    private final ColumnFamilyHandle metadataHandle;
    private final ColumnFamilyHandle slotToHashHandle;

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
                    new ColumnFamilyDescriptor("number_by_slot".getBytes()),
                    new ColumnFamilyDescriptor("slot_by_number".getBytes()),
                    new ColumnFamilyDescriptor("slot_to_hash".getBytes()),
                    new ColumnFamilyDescriptor("metadata".getBytes())
            );

            // Open database
            final List<ColumnFamilyHandle> cfHandles = new ArrayList<>();
            db = RocksDB.open(dbOptions, dbPath, cfDescriptors, cfHandles);

            // Assign handles (skip default at index 0)
            blocksHandle = cfHandles.get(1);
            headersHandle = cfHandles.get(2);
            numberBySlotHandle = cfHandles.get(3);
            slotByNumberHandle = cfHandles.get(4);
            slotToHashHandle = cfHandles.get(5);
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
            // Validate against header recorded for this slot
            byte[] expectedHash = db.get(slotToHashHandle, longToBytes(slot));
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
                    // MAIN header path (default): also update number -> slot mapping
                    batch.put(slotByNumberHandle, longToBytes(blockNumber), longToBytes(slot));
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

    // Store Byron EBB header without updating number->slot mapping (EBB has same difficulty as preceding main)
    public void storeByronEbHeader(byte[] blockHash, Long blockNumber, Long slot, byte[] blockHeader) {
        try (WriteBatch batch = new WriteBatch()) {
            batch.put(headersHandle, blockHash, blockHeader);
            ChainTip newHeaderTip = new ChainTip(slot, blockHash, blockNumber);
            batch.put(metadataHandle, HEADER_TIP_KEY, serializeChainTip(newHeaderTip));
            if (slot != null && blockNumber != null) {
                // slot-first indices only
                batch.put(numberBySlotHandle, longToBytes(slot), longToBytes(blockNumber));
                batch.put(slotToHashHandle, longToBytes(slot), blockHash);
            }
            db.write(new WriteOptions(), batch);
            log.debug("Stored Byron EBB header: slot={}, blockNumber={}", slot, blockNumber);
        } catch (Exception e) {
            throw new RuntimeException("Failed to store Byron EBB header", e);
        }
    }

    @Override
    public byte[] getBlockByNumber(Long blockNumber) {
        try {
            byte[] slotBytes = db.get(slotByNumberHandle, longToBytes(blockNumber));
            if (slotBytes != null) {
                long slot = bytesToLong(slotBytes);
                byte[] blockHash = db.get(slotToHashHandle, longToBytes(slot));
                if (blockHash != null) {
                    return db.get(blocksHandle, blockHash);
                }
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
            byte[] slotBytes = db.get(slotByNumberHandle, longToBytes(blockNumber));
            if (slotBytes != null) {
                long slot = bytesToLong(slotBytes);
                byte[] blockHash = db.get(slotToHashHandle, longToBytes(slot));
                if (blockHash != null) {
                    return db.get(headersHandle, blockHash);
                }
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
            byte[] rollbackHash = db.get(slotToHashHandle, longToBytes(slot));

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
                    byte[] blockHash = db.get(slotToHashHandle, iterator.key());

                    if (blockHash != null) {
                        // Only delete header data (not checking for bodies as this is header-only)
                        byte[] headerData = db.get(headersHandle, blockHash);
                        if (headerData != null) {
                            batch.delete(headersHandle, blockHash);
                            headersDeleted++;
                        }

                        // Delete mappings
                        batch.delete(numberBySlotHandle, iterator.key());
                        batch.delete(slotByNumberHandle, longToBytes(blockNumber));
                        // Delete slot->hash mapping
                        batch.delete(slotToHashHandle, iterator.key());
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
                    // Resolve aligned hash by its slot mapping
                    byte[] slotBytes = db.get(slotByNumberHandle, longToBytes(alignedBlockNumber));
                    byte[] alignedHash = null;
                    long alignedSlot = 0;
                    if (slotBytes != null) {
                        alignedSlot = bytesToLong(slotBytes);
                        alignedHash = db.get(slotToHashHandle, longToBytes(alignedSlot));
                    }

                    if (alignedHash != null) {
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
                    byte[] blockHash = db.get(slotToHashHandle, iterator.key());

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
                        batch.delete(numberBySlotHandle, iterator.key());
                        batch.delete(slotByNumberHandle, longToBytes(blockNumber));
                        batch.delete(slotToHashHandle, iterator.key());
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

            // If current slot is already at or beyond tip, no next block available
            if (currentSlot >= tipSlot) {
                if (log.isDebugEnabled())
                    log.debug("Current slot {} is at or beyond tip slot {}, no next block", currentSlot, tipSlot);
                return null;
            }

            // Handle Point.ORIGIN (slot 0) specially: return the first slot in slot_to_hash
            try (RocksIterator iterator = db.newIterator(slotToHashHandle)) {
                if (currentSlot == 0 && currentPoint.getHash() == null) {
                    iterator.seekToFirst();
                    if (iterator.isValid()) {
                        long nextSlot = bytesToLong(iterator.key());
                        byte[] hash = iterator.value();
                        Point p = new Point(nextSlot, HexUtil.encodeHexString(hash));
                        if (log.isDebugEnabled()) log.debug("Returning first block after ORIGIN: {}", p);
                        return p;
                    } else {
                        return null;
                    }
                }

                // Seek to currentSlot, then move to strictly greater slot if needed
                iterator.seek(longToBytes(currentSlot));
                if (iterator.isValid()) {
                    long foundSlot = bytesToLong(iterator.key());
                    if (foundSlot <= currentSlot) iterator.next();
                }

                if (!iterator.isValid()) {
                    if (log.isDebugEnabled()) log.debug("Reached end of slot_to_hash; no next block after slot {}", currentSlot);
                    return null;
                }

                long nextSlot = bytesToLong(iterator.key());
                byte[] hash = iterator.value();
                if (nextSlot > tipSlot) {
                    if (log.isDebugEnabled()) log.debug("Next slot {} is beyond tip slot {}", nextSlot, tipSlot);
                    return null;
                }

                Point next = new Point(nextSlot, HexUtil.encodeHexString(hash));
                if (log.isDebugEnabled()) log.debug("Next block by slot: {}", next);
                return next;
            }

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

            try (RocksIterator iterator = db.newIterator(slotToHashHandle)) {
                // ORIGIN: return first header
                if (currentSlot == 0 && currentPoint.getHash() == null) {
                    iterator.seekToFirst();
                    if (iterator.isValid()) {
                        long nextSlot = bytesToLong(iterator.key());
                        byte[] hash = iterator.value();
                        return new Point(nextSlot, HexUtil.encodeHexString(hash));
                    }
                    return null;
                }

                // Seek to current slot and advance to strictly greater one
                iterator.seek(longToBytes(currentSlot));
                if (iterator.isValid()) {
                    long found = bytesToLong(iterator.key());
                    if (found <= currentSlot) iterator.next();
                }

                if (!iterator.isValid()) return null;

                long nextSlot = bytesToLong(iterator.key());
                byte[] hash = iterator.value();
                if (nextSlot > headerTip.getSlot()) return null;
                return new Point(nextSlot, HexUtil.encodeHexString(hash));
            }

        } catch (Exception e) {
            log.error("Failed to find next block header after slot {}", currentPoint.getSlot(), e);
            return null;
        }
    }

    @Override
    public List<Point> findBlocksInRange(Point from, Point to) {
        List<Point> blocks = new ArrayList<>();
        try (RocksIterator iterator = db.newIterator(slotToHashHandle)) {
            long fromSlot = from.getSlot();
            long toSlot = to.getSlot();

            iterator.seek(longToBytes(fromSlot));
            while (iterator.isValid()) {
                long currentSlot = bytesToLong(iterator.key());
                if (currentSlot > toSlot) break;
                byte[] hash = iterator.value();
                blocks.add(new Point(currentSlot, HexUtil.encodeHexString(hash)));
                iterator.next();
            }
            return blocks;
        } catch (Exception e) {
            log.error("Failed to find blocks in range", e);
            return blocks;
        }
    }


    @Override
    public Point findLastPointAfterNBlocks(Point from, long batchSize) {
        if (log.isDebugEnabled())
            log.debug("🔍 findLastPointAfterNBlocks called: from={}, batchSize={}", from, batchSize);

        long lastSlot = 0;
        try (RocksIterator iterator = db.newIterator(slotToHashHandle)) {
            long fromSlot = from.getSlot();
            iterator.seek(longToBytes(fromSlot));

            int counter = 0;
            while (counter < batchSize && iterator.isValid()) {
                lastSlot = bytesToLong(iterator.key());
                iterator.next();
                counter++;
            }

            if (counter == 0) return null;
            byte[] hash = db.get(slotToHashHandle, longToBytes(lastSlot));
            if (hash == null) return null;
            Point result = new Point(lastSlot, HexUtil.encodeHexString(hash));
            if (log.isDebugEnabled()) log.debug("✅ findLastPointAfterNBlocks returning: {}", result);
            return result;
        } catch (Exception e) {
            log.error("Failed to find last point after n blocks", e);
            return null;
        }
    }

    @Override
    public boolean hasPoint(Point point) {
        try {
            byte[] blockHash = db.get(slotToHashHandle, longToBytes(point.getSlot()));
            if (blockHash == null) return false;
            if (point.getHash() == null) return true;
            return HexUtil.encodeHexString(blockHash).equals(point.getHash());
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
        try (RocksIterator iterator = db.newIterator(slotToHashHandle)) {
            iterator.seekToFirst();
            if (!iterator.isValid()) return null;
            long slot = bytesToLong(iterator.key());
            byte[] hash = iterator.value();
            return new Point(slot, HexUtil.encodeHexString(hash));
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
     * 1. Computes last continuous header/body block numbers up to their tips
     * 2. Removes all data after the recovery point
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

            if (headerTip == null && bodyTip == null) return false;

            // Sanity check: body tip's body must exist
            if (bodyTip != null) {
                byte[] tipHash = db.get(slotToHashHandle, longToBytes(bodyTip.getSlot()));
                if (tipHash == null) return true;
                byte[] tipBody = db.get(blocksHandle, tipHash);
                if (tipBody == null) return true;
            }

            long maxSlot = 0;
            if (headerTip != null) maxSlot = Math.max(maxSlot, headerTip.getSlot());
            if (bodyTip != null) maxSlot = Math.max(maxSlot, bodyTip.getSlot());

            long startSlot = Math.max(0, maxSlot - 1000);

            try (RocksIterator it = db.newIterator(slotToHashHandle)) {
                it.seek(longToBytes(startSlot));
                while (it.isValid()) {
                    long slot = bytesToLong(it.key());
                    if (slot > maxSlot) break;
                    byte[] hash = it.value();
                    if (hash == null) return true;
                    if (bodyTip != null && slot <= bodyTip.getSlot()) {
                        byte[] body = db.get(blocksHandle, hash);
                        if (body == null) return true;
                    }
                    it.next();
                }
            }

            return false;

        } catch (Exception e) {
            log.warn("Error during corruption detection", e);
            return false; // Assume not corrupted if we can't check
        }
    }

    /**
     * Find the last block where header and body have matching hashes
     */
    private long findLastAlignedBlock(long maxBlockNumber) throws RocksDBException {
        log.info("🔍 Searching for last aligned block where header and body hashes match (slot-based)...");

        // Determine starting slot from block number if possible
        long startSlot = 0;
        byte[] slotBytes = db.get(slotByNumberHandle, longToBytes(maxBlockNumber));
        if (slotBytes != null) startSlot = bytesToLong(slotBytes);

        try (RocksIterator it = db.newIterator(slotToHashHandle)) {
            if (startSlot > 0) {
                it.seekForPrev(longToBytes(startSlot));
            } else {
                it.seekToLast();
            }
            while (it.isValid()) {
                long slot = bytesToLong(it.key());
                byte[] hash = it.value();
                if (hash != null) {
                    byte[] header = db.get(headersHandle, hash);
                    byte[] body = db.get(blocksHandle, hash);
                    if (header != null && body != null) {
                        Long number = getBlockNumberBySlot(slot);
                        long bn = number != null ? number : 0L;
                        log.info("✅ Found aligned block at slot {} (number {}): header and body present", slot, bn);
                        return bn;
                    }
                }
                it.prev();
            }
        }

        log.warn("Could not find aligned block by slot");
        return 0;
    }

    /**
     * Find the last block number where headers form a continuous sequence.
     * Scans backward from the current header tip until a valid header with consistent indices is found.
     */
    private Long findLastContinuousHeaderBlock() throws RocksDBException {
        log.info("🔍 Scanning backward for last continuous header from header tip...");

        ChainTip headerTip = getHeaderTip();
        if (headerTip == null) {
            log.info("No header tip present; cannot determine header continuity");
            return null;
        }

        try (RocksIterator it = db.newIterator(slotToHashHandle)) {
            // Start from header tip slot and walk backward
            it.seekForPrev(longToBytes(headerTip.getSlot()));
            while (it.isValid()) {
                long slot = bytesToLong(it.key());
                byte[] hash = it.value();
                byte[] header = db.get(headersHandle, hash);
                if (header != null) {
                    Long number = getBlockNumberBySlot(slot);
                    log.info("📄 Last continuous header determined at slot {} (number {})", slot, number);
                    return number != null ? number : 0L;
                }
                it.prev();
            }
        }

        log.warn("📄 Could not find any valid continuous header block");
        return null;
    }

    /**
     * Find the last block number where bodies form a continuous sequence.
     * Scans backward from the current body tip until a valid body with consistent indices is found.
     */
    private Long findLastContinuousBodyBlock() throws RocksDBException {
        log.info("🔍 Scanning backward for last continuous body from body tip...");

        ChainTip bodyTip = getTip();
        if (bodyTip == null) {
            log.info("No body tip present; cannot determine body continuity");
            return null;
        }

        try (RocksIterator it = db.newIterator(slotToHashHandle)) {
            it.seekForPrev(longToBytes(bodyTip.getSlot()));
            while (it.isValid()) {
                long slot = bytesToLong(it.key());
                byte[] hash = it.value();
                byte[] body = db.get(blocksHandle, hash);
                if (body != null) {
                    Long number = getBlockNumberBySlot(slot);
                    log.info("🧱 Last continuous body determined at slot {} (number {})", slot, number);
                    return number != null ? number : 0L;
                }
                it.prev();
            }
        }

        log.warn("🧱 Could not find any valid continuous body block");
        return null;
    }

    /**
     * Close the database connection
     */
    public void close() {
        try {
            blocksHandle.close();
            headersHandle.close();
            numberBySlotHandle.close();
            slotByNumberHandle.close();
            slotToHashHandle.close();
            metadataHandle.close();
            db.close();
        } catch (Exception e) {
            log.error("Failed to close RocksDB", e);
        }
    }

    // Helper methods

    private void updateChainState(WriteBatch batch, byte[] blockHash, Long blockNumber, Long slot) throws RocksDBException {
        // Store mappings (slot-first)
        batch.put(numberBySlotHandle, longToBytes(slot), longToBytes(blockNumber));
        batch.put(slotToHashHandle, longToBytes(slot), blockHash);
        // NOTE: slot_by_number will be written only for MAIN blocks (not EBB) by specialized methods
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
