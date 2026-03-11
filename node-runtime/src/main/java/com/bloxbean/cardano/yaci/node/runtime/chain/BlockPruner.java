package com.bloxbean.cardano.yaci.node.runtime.chain;

import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.node.runtime.utxo.Prunable;
import org.rocksdb.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Prunes block bodies from the {@code blocks} column family beyond a configurable
 * retention window. Headers, indices, and metadata are preserved.
 * <p>
 * Algorithm:
 * <ol>
 *   <li>Compute cutoff: currentBlockNumber - retentionBlocks</li>
 *   <li>Iterate {@code slot_by_number} CF from cursor to cutoff block number</li>
 *   <li>For each blockNumber → slot, look up {@code slot_to_hash} to get hash</li>
 *   <li>Delete hash key from {@code blocks} CF</li>
 *   <li>Persist cursor in {@code metadata} CF</li>
 * </ol>
 * <p>
 * Implements {@link Prunable} so it can be scheduled via {@link com.bloxbean.cardano.yaci.node.runtime.utxo.PruneService}.
 */
public final class BlockPruner implements Prunable {

    private static final Logger log = LoggerFactory.getLogger(BlockPruner.class);
    private static final byte[] CURSOR_KEY = "prune.block.cursor".getBytes(StandardCharsets.UTF_8);

    private final DirectRocksDBChainState chainState;
    private final int retentionBlocks;
    private final int batchSize;

    public BlockPruner(DirectRocksDBChainState chainState, int retentionBlocks, int batchSize) {
        this.chainState = chainState;
        this.retentionBlocks = retentionBlocks;
        this.batchSize = batchSize;
    }

    @Override
    public void pruneOnce() {
        try {
            var ctx = chainState.rocks();
            RocksDB db = ctx.db();
            ColumnFamilyHandle slotByNumberCf = ctx.handle("slot_by_number");
            ColumnFamilyHandle slotToHashCf = ctx.handle("slot_to_hash");
            ColumnFamilyHandle blocksCf = ctx.handle("blocks");
            ColumnFamilyHandle metadataCf = ctx.handle("metadata");

            if (slotByNumberCf == null || slotToHashCf == null || blocksCf == null || metadataCf == null) {
                return;
            }

            ChainTip tip = chainState.getTip();
            if (tip == null || tip.getBlockNumber() <= retentionBlocks) {
                return; // not enough blocks yet
            }

            long cutoff = tip.getBlockNumber() - retentionBlocks;

            // Read cursor (-1 means never pruned)
            long cursor = -1;
            byte[] cursorBytes = db.get(metadataCf, CURSOR_KEY);
            if (cursorBytes != null && cursorBytes.length == 8) {
                cursor = ByteBuffer.wrap(cursorBytes).getLong();
            }

            if (cursor >= cutoff) {
                return; // already pruned up to cutoff
            }

            int deleted = 0;
            try (WriteBatch batch = new WriteBatch();
                 WriteOptions wo = new WriteOptions();
                 RocksIterator it = db.newIterator(slotByNumberCf)) {

                byte[] startKey = longToBytes(cursor + 1);
                it.seek(startKey);

                long lastPrunedBlock = cursor;

                while (it.isValid() && deleted < batchSize) {
                    byte[] key = it.key();
                    if (key.length != 8) {
                        it.next();
                        continue;
                    }
                    long blockNumber = ByteBuffer.wrap(key).getLong();
                    if (blockNumber > cutoff) break;

                    byte[] slotBytes = it.value();
                    if (slotBytes != null && slotBytes.length == 8) {
                        long slot = ByteBuffer.wrap(slotBytes).getLong();
                        byte[] blockHash = db.get(slotToHashCf, longToBytes(slot));
                        if (blockHash != null) {
                            // Only delete if block body actually exists (idempotent)
                            byte[] existing = db.get(blocksCf, blockHash);
                            if (existing != null) {
                                batch.delete(blocksCf, blockHash);
                                deleted++;
                            }
                        }
                    }

                    lastPrunedBlock = blockNumber;
                    it.next();
                }

                if (deleted > 0) {
                    batch.put(metadataCf, CURSOR_KEY, longToBytes(lastPrunedBlock));
                    db.write(wo, batch);
                    log.info("Block pruner: deleted {} block bodies, cursor now at block {}", deleted, lastPrunedBlock);
                }
            }
        } catch (Exception e) {
            log.warn("Block prune error: {}", e.toString());
        }
    }

    private static byte[] longToBytes(long value) {
        return ByteBuffer.allocate(Long.BYTES).putLong(value).array();
    }
}
