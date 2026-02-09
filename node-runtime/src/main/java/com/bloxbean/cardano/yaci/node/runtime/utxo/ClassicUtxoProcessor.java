package com.bloxbean.cardano.yaci.node.runtime.utxo;

import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.node.runtime.events.BlockAppliedEvent;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDB;

import java.util.*;

/**
 * Simple processor that batches unspent lookups for inputs/collaterals using MultiGet.
 */
final class ClassicUtxoProcessor implements UtxoProcessor {
    private final RocksDB db;

    ClassicUtxoProcessor(RocksDB db) {
        this.db = db;
    }

    @Override
    public ApplyContext prepare(BlockAppliedEvent event, ColumnFamilyHandle cfUnspent) {
        if (event.block() == null) return k -> null;
        var block = event.block();
        List<TransactionBody> txs = block.getTransactionBodies();
        if (txs == null || txs.isEmpty()) return k -> null;

        // Collect outpoint keys for inputs of valid txs and collateral inputs of invalid txs
        Set<Integer> invalidIdx = new HashSet<>();
        if (block.getInvalidTransactions() != null) invalidIdx.addAll(block.getInvalidTransactions());

        List<byte[]> keys = new ArrayList<>();
        for (int i = 0; i < txs.size(); i++) {
            var tx = txs.get(i);
            boolean invalid = invalidIdx.contains(i);
            if (!invalid) {
                if (tx.getInputs() != null) for (var in : tx.getInputs()) {
                    byte[] key = UtxoKeyUtil.outpointKey(in.getTransactionId(), in.getIndex());
                    keys.add(key);
                }
            } else {
                if (tx.getCollateralInputs() != null) for (var in : tx.getCollateralInputs()) {
                    byte[] key = UtxoKeyUtil.outpointKey(in.getTransactionId(), in.getIndex());
                    keys.add(key);
                }
            }
        }

        Map<ByteArrayWrapper, byte[]> cache = new HashMap<>((int)(keys.size() * 1.5));
        if (!keys.isEmpty()) {
            try {
                // Use multiGetAsList with repeated CF handle matching the keys list size
                java.util.List<ColumnFamilyHandle> cfs = new ArrayList<>(keys.size());
                for (int i = 0; i < keys.size(); i++) cfs.add(cfUnspent);
                java.util.List<byte[]> vals = db.multiGetAsList(cfs, keys);
                for (int i = 0; i < keys.size(); i++) {
                    cache.put(new ByteArrayWrapper(keys.get(i)), vals.get(i));
                }
            } catch (Throwable t) {
                for (byte[] k : keys) {
                    try { cache.put(new ByteArrayWrapper(k), db.get(cfUnspent, k)); }
                    catch (Exception ignored) {}
                }
            }
        }

        return new ApplyContext() {
            @Override public byte[] getUnspent(byte[] outpointKey) { return cache.get(new ByteArrayWrapper(outpointKey)); }
        };
    }

    private static final class ByteArrayWrapper {
        private final byte[] b;
        private final int h;
        ByteArrayWrapper(byte[] b) { this.b = b; this.h = Arrays.hashCode(b); }
        @Override public boolean equals(Object o) { return (o instanceof ByteArrayWrapper w) && Arrays.equals(b, w.b); }
        @Override public int hashCode() { return h; }
    }
}
