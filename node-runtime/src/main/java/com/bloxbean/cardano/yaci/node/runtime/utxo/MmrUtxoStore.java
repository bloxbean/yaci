package com.bloxbean.cardano.yaci.node.runtime.utxo;

import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.node.runtime.db.RocksDbSupplier;
import com.bloxbean.cardano.yaci.node.runtime.events.RollbackEvent;
import org.rocksdb.*;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MMR UTXO Store (initial implementation):
 * - Delegates full read/write semantics to ClassicUtxoStore to ensure feature parity.
 * - Additionally persists a simple per-block MMR node value into UTXO_MMR_NODES CF to lay groundwork for MMR commitments.
 * - Exposes storeType() = "mmr" for status.
 *
 * NOTE: This is a stepping stone. Future iterations will replace the delegate write path with true MMR-backed persistence.
 */
public final class MmrUtxoStore implements com.bloxbean.cardano.yaci.node.api.utxo.UtxoState,
        com.bloxbean.cardano.yaci.node.api.utxo.UtxoMmrState,
        UtxoStoreWriter, Prunable, UtxoStatusProvider, AutoCloseable {
    private final ClassicUtxoStore delegate;
    private final RocksDB db;
    private final ColumnFamilyHandle cfMmrNodes;
    private final ColumnFamilyHandle cfMeta;
    private final Logger log;

    public MmrUtxoStore(RocksDbSupplier supplier, Logger logger, Map<String, Object> config) {
        this.delegate = new ClassicUtxoStore(supplier, logger, config);
        this.db = supplier.rocks().db();
        this.cfMmrNodes = supplier.rocks().handle(com.bloxbean.cardano.yaci.node.runtime.db.UtxoCfNames.UTXO_MMR_NODES);
        this.cfMeta = supplier.rocks().handle(com.bloxbean.cardano.yaci.node.runtime.db.UtxoCfNames.UTXO_META);
        this.log = logger;
        log.info("MmrUtxoStore initialized (delegating to Classic for base semantics)");
    }

    // ----- UtxoState (read API) -----
    @Override public List<com.bloxbean.cardano.yaci.node.api.utxo.model.Utxo> getUtxosByAddress(String bech32OrHexAddress, int page, int pageSize) { return delegate.getUtxosByAddress(bech32OrHexAddress, page, pageSize); }
    @Override public List<com.bloxbean.cardano.yaci.node.api.utxo.model.Utxo> getUtxosByPaymentCredential(String credentialHexOrAddress, int page, int pageSize) { return delegate.getUtxosByPaymentCredential(credentialHexOrAddress, page, pageSize); }
    @Override public Optional<com.bloxbean.cardano.yaci.node.api.utxo.model.Utxo> getUtxo(com.bloxbean.cardano.yaci.node.api.utxo.model.Outpoint outpoint) { return delegate.getUtxo(outpoint); }
    @Override public boolean isEnabled() { return delegate.isEnabled(); }

    // ----- UtxoStoreWriter -----
    @Override
    public void apply(MultiEraBlockTxs nb) {
        long beforeBlock = delegate.readLastAppliedBlock();
        delegate.apply(nb);
        long appliedBlock = delegate.readLastAppliedBlock();
        int createdThisBlock = 0;
        if (appliedBlock > beforeBlock) {
            // Append leaves for each created outpoint in this block and persist proofs/mappings
            try (WriteBatch wb = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
                if (nb != null && nb.txs != null) {
                    for (var tx : nb.txs) {
                        if (tx.invalid || tx.outputs == null) continue; // ignore invalid tx outputs
                        for (int outIdx = 0; outIdx < tx.outputs.size(); outIdx++) {
                            byte[] outKey = UtxoKeyUtil.outpointKey(tx.txHash, outIdx);
                            byte[] unspentVal = delegate.rawUnspentValue(outKey);
                            if (unspentVal == null) continue;
                            byte[] leaf = leafHash(outKey, unspentVal);
                            // Build proof while updating peaks
                            java.util.List<byte[]> peaks = readPeaks();
                            java.util.List<byte[]> path = new java.util.ArrayList<>();
                            byte[] carry = leaf;
                            int h = 0;
                            while (h < peaks.size() && peaks.get(h) != null) {
                                byte[] sib = peaks.get(h);
                                path.add(sib);
                                carry = parentHash(sib, carry);
                                peaks.set(h, null);
                                h++;
                            }
                            if (h >= peaks.size()) peaks.add(carry);
                            else peaks.set(h, carry);
                            writePeaks(wb, peaks);
                            long leafIndex = readLeafCount() + 1;
                            writeLeafCount(wb, leafIndex);
                            // Persist proof and mapping
                            wb.put(cfMeta, keyProof(leafIndex), concat(path));
                            wb.put(cfMeta, keyLeafOutpoint(leafIndex), outKey);
                            wb.put(cfMeta, keyLeafHash(leafIndex), leaf);
                            wb.put(cfMeta, keyOutpointToLeaf(outKey), ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(leafIndex).array());
                            createdThisBlock++;
                        }
                    }
                }
                // Record block created count for rollback trimming
                wb.put(cfMeta, keyBlockCreatedCount(appliedBlock), ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(createdThisBlock).array());
                // Also write a simple node record for this block under UTXO_MMR_NODES to satisfy API/tests
                // payload layout: slot(8) | blockNo(8) | createdCount(4) | reserved(4)
                ByteBuffer buf = ByteBuffer.allocate(8 + 8 + 4 + 4).order(ByteOrder.BIG_ENDIAN);
                buf.putLong(nb != null ? nb.slot : 0L);
                buf.putLong(appliedBlock);
                buf.putInt(createdThisBlock);
                buf.putInt(0);
                byte[] nodePayload = buf.array();
                byte[] k = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(appliedBlock).array();
                wb.put(cfMmrNodes, k, nodePayload);
                db.write(wo, wb);
            } catch (Exception ex) {
                log.warn("MMR append failed for block {}: {}", appliedBlock, ex.toString());
            }
        }
    }

    @Override
    public void rollbackTo(RollbackEvent e) {
        delegate.rollbackTo(e);
        // After delegate rollback, pop MMR leaves for blocks beyond new last applied
        long newLastBlock = delegate.readLastAppliedBlock();
        try (WriteBatch wb = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            // Determine how many blocks to trim by scanning block_created_count keys backwards
            long leafCount = readLeafCount();
            // Walk down from the last applied block+1 and remove entries
            long probeBlock = newLastBlock + 1;
            while (true) {
                byte[] k = keyBlockCreatedCount(probeBlock);
                byte[] v = db.get(cfMeta, k);
                if (v == null) break;
                int created = ByteBuffer.wrap(v).order(ByteOrder.BIG_ENDIAN).getInt();
                // Delete created leaves mappings and proofs
                for (int i = 0; i < created; i++) {
                    if (leafCount <= 0) break;
                    long li = leafCount;
                    byte[] outKey = db.get(cfMeta, keyLeafOutpoint(li));
                    if (outKey != null) wb.delete(cfMeta, keyOutpointToLeaf(outKey));
                    wb.delete(cfMeta, keyLeafOutpoint(li));
                    wb.delete(cfMeta, keyProof(li));
                    leafCount--;
                }
                // Remove block count record
                wb.delete(cfMeta, k);
                probeBlock++;
            }
            writeLeafCount(wb, leafCount);
            // Reset peaks; they will be rebuilt on next reconcile if needed
            writePeaks(wb, new java.util.ArrayList<>());
            db.write(wo, wb);
        } catch (Exception ex) {
            log.warn("Failed to trim MMR after rollback: {}", ex.toString());
        }
    }

    // reconcile implemented later below with peaks rebuild

    // ----- Prunable -----
    @Override public void pruneOnce() { delegate.pruneOnce(); /* Future: prune MMR nodes if needed */ }

    // ----- UtxoStatusProvider -----
    @Override public String storeType() { return "mmr"; }
    @Override public long getLastAppliedBlock() { return delegate.getLastAppliedBlock(); }
    @Override public long getLastAppliedSlot() { return delegate.getLastAppliedSlot(); }
    @Override public int getPruneDepth() { return delegate.getPruneDepth(); }
    @Override public int getRollbackWindow() { return delegate.getRollbackWindow(); }
    @Override public int getPruneBatchSize() { return delegate.getPruneBatchSize(); }
    @Override public byte[] getDeltaCursorKey() { return delegate.getDeltaCursorKey(); }
    @Override public byte[] getSpentCursorKey() { return delegate.getSpentCursorKey(); }
    @Override public Map<String, Object> getMetrics() {
        java.util.Map<String, Object> base = new java.util.HashMap<>(delegate.getMetrics());
        try {
            long leaves = getMmrLeafCount();
            java.util.List<byte[]> peaks = readPeaks();
            base.put("mmr.leaf.count", leaves);
            base.put("mmr.root", getMmrRootHex());
            base.put("mmr.peaks.count", peaks != null ? peaks.stream().filter(p -> p != null).count() : 0);
        } catch (Throwable ignored) {}
        return base;
    }
    @Override public Map<String, Long> getCfEstimates() { return delegate.getCfEstimates(); }

    @Override public void close() { try { delegate.close(); } catch (Exception ignored) {} }

    // ----- UtxoMmrState -----
    @Override public long getMmrLeafCount() { return readLeafCount(); }
    @Override public String getMmrRootHex() { return com.bloxbean.cardano.yaci.core.util.HexUtil.encodeHexString(bagPeaks(readPeaks())); }
    @Override public java.util.Optional<Long> getLeafIndex(com.bloxbean.cardano.yaci.node.api.utxo.model.Outpoint outpoint) {
        try {
            byte[] idx = db.get(cfMeta, keyOutpointToLeaf(UtxoKeyUtil.outpointKey(outpoint.txHash(), outpoint.index())));
            if (idx == null || idx.length != 8) return java.util.Optional.empty();
            return java.util.Optional.of(ByteBuffer.wrap(idx).order(ByteOrder.BIG_ENDIAN).getLong());
        } catch (Exception e1) { return java.util.Optional.empty(); }
    }
    @Override public java.util.Optional<com.bloxbean.cardano.yaci.node.api.utxo.model.MmrProof> getProof(long leafIndex) {
        try {
            byte[] path = db.get(cfMeta, keyProof(leafIndex));
            if (path == null) return java.util.Optional.empty();
            java.util.List<String> hex = new java.util.ArrayList<>();
            for (int i = 0; i + 32 <= path.length; i += 32) hex.add(com.bloxbean.cardano.yaci.core.util.HexUtil.encodeHexString(java.util.Arrays.copyOfRange(path, i, i + 32)));
            String root = getMmrRootHex();
            return java.util.Optional.of(new com.bloxbean.cardano.yaci.node.api.utxo.model.MmrProof(leafIndex, hex, root));
        } catch (Exception e) { return java.util.Optional.empty(); }
    }

    // ----- Internal helpers -----
    private static final byte[] META_MMR_LEAF_COUNT = "mmr.leaf.count".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final byte[] META_MMR_PEAKS = "mmr.peaks".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static byte[] keyProof(long leafIndex) { return ("mmr.proof:" + leafIndex).getBytes(java.nio.charset.StandardCharsets.UTF_8); }
    private static byte[] keyLeafOutpoint(long leafIndex) { return ("mmr.leaf.outpoint:" + leafIndex).getBytes(java.nio.charset.StandardCharsets.UTF_8); }
    private static byte[] keyOutpointToLeaf(byte[] outKey) { return com.bloxbean.cardano.yaci.core.util.HexUtil.encodeHexString(outKey).concat("|mmr.idx").getBytes(java.nio.charset.StandardCharsets.UTF_8); }
    private static byte[] keyBlockCreatedCount(long blockNo) { return ("mmr.block.created:" + blockNo).getBytes(java.nio.charset.StandardCharsets.UTF_8); }
    private static byte[] keyLeafHash(long leafIndex) { return ("mmr.leaf.hash:" + leafIndex).getBytes(java.nio.charset.StandardCharsets.UTF_8); }

    private long readLeafCount() {
        try { byte[] v = db.get(cfMeta, META_MMR_LEAF_COUNT); return v != null && v.length == 8 ? ByteBuffer.wrap(v).order(ByteOrder.BIG_ENDIAN).getLong() : 0L; }
        catch (Exception e) { return 0L; }
    }
    private void writeLeafCount(WriteBatch wb, long cnt) {
        try { wb.put(cfMeta, META_MMR_LEAF_COUNT, ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(cnt).array()); }
        catch (org.rocksdb.RocksDBException ignored) {}
    }

    private java.util.List<byte[]> readPeaks() {
        try {
            byte[] v = db.get(cfMeta, META_MMR_PEAKS);
            java.util.List<byte[]> peaks = new java.util.ArrayList<>();
            if (v == null || v.length == 0) return peaks;
            int n = v[0] & 0xff; int off = 1;
            for (int i = 0; i < n; i++) { peaks.add(java.util.Arrays.copyOfRange(v, off, off + 32)); off += 32; }
            return peaks;
        } catch (Exception e) { return new java.util.ArrayList<>(); }
    }
    private void writePeaks(WriteBatch wb, java.util.List<byte[]> peaks) {
        int n = 0; for (byte[] p : peaks) if (p != null) n++;
        byte[] buf = new byte[1 + 32 * n]; buf[0] = (byte) n; int off = 1;
        for (byte[] p : peaks) if (p != null) { System.arraycopy(p, 0, buf, off, 32); off += 32; }
        try { wb.put(cfMeta, META_MMR_PEAKS, buf); }
        catch (org.rocksdb.RocksDBException ignored) {}
    }

    private static byte[] leafHash(byte[] outpointKey, byte[] unspentVal) {
        byte[] uvHash = com.bloxbean.cardano.client.crypto.Blake2bUtil.blake2bHash256(unspentVal);
        byte[] buf = new byte[1 + outpointKey.length + uvHash.length];
        buf[0] = 0x00; System.arraycopy(outpointKey, 0, buf, 1, outpointKey.length); System.arraycopy(uvHash, 0, buf, 1 + outpointKey.length, uvHash.length);
        return com.bloxbean.cardano.client.crypto.Blake2bUtil.blake2bHash256(buf);
    }
    private static byte[] parentHash(byte[] left, byte[] right) {
        byte[] buf = new byte[1 + left.length + right.length]; buf[0] = 0x01; System.arraycopy(left, 0, buf, 1, left.length); System.arraycopy(right, 0, buf, 1 + left.length, right.length);
        return com.bloxbean.cardano.client.crypto.Blake2bUtil.blake2bHash256(buf);
    }
    private static byte[] bagPeaks(java.util.List<byte[]> peaks) {
        byte[] acc = null;
        for (byte[] p : peaks) if (p != null) acc = acc == null ? p : parentHash(acc, p);
        return acc == null ? new byte[32] : acc;
    }
    private static byte[] concat(java.util.List<byte[]> parts) {
        int len = 0; for (byte[] p : parts) len += p.length;
        byte[] buf = new byte[len]; int off = 0; for (byte[] p : parts) { System.arraycopy(p, 0, buf, off, p.length); off += p.length; }
        return buf;
    }

    // Rebuild peaks using stored leaf hashes 1..leafCount; O(N) routine used on reconcile after resets
    private void rebuildPeaksAndCounts() {
        long n = readLeafCount();
        if (n <= 0) return;
        java.util.List<byte[]> peaks = new java.util.ArrayList<>();
        for (long i = 1; i <= n; i++) {
            try {
                byte[] leaf = db.get(cfMeta, keyLeafHash(i));
                if (leaf == null || leaf.length != 32) continue;
                byte[] carry = leaf;
                int h = 0;
                while (h < peaks.size() && peaks.get(h) != null) {
                    carry = parentHash(peaks.get(h), carry);
                    peaks.set(h, null);
                    h++;
                }
                if (h >= peaks.size()) peaks.add(carry); else peaks.set(h, carry);
            } catch (Exception ignored) {}
        }
        try (WriteBatch wb = new WriteBatch(); WriteOptions wo = new WriteOptions()) {
            writePeaks(wb, peaks);
            db.write(wo, wb);
        } catch (Exception ignored) {}
    }

    @Override
    public void reconcile(ChainState chainState) {
        delegate.reconcile(chainState);
        // If peaks are empty but leaves exist, rebuild peaks from leaf hashes
        try {
            java.util.List<byte[]> peaks = readPeaks();
            if ((peaks == null || peaks.isEmpty()) && readLeafCount() > 0) rebuildPeaksAndCounts();
        } catch (Exception ignored) {}
    }
}
