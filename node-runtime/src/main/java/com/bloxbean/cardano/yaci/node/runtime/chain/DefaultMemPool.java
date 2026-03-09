package com.bloxbean.cardano.yaci.node.runtime.chain;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.yaci.core.common.TxBodyType;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public class DefaultMemPool implements MemPool {
    // LinkedHashMap preserves insertion order for efficient oldest-first iteration
    private final LinkedHashMap<String, MemPoolTransaction> txIndex;
    private final AtomicLong cursor = new AtomicLong(0);

    public DefaultMemPool() {
        this.txIndex = new LinkedHashMap<>();
    }

    @Override
    public synchronized MemPoolTransaction addTransaction(byte[] txBytes) {
        var txHash = TransactionUtil.getTxHash(txBytes);
        // Deduplicate: skip if already present
        if (txIndex.containsKey(txHash)) {
            return txIndex.get(txHash);
        }
        long txSeqId = cursor.incrementAndGet();
        var memPoolTransaction = new MemPoolTransaction(txSeqId, HexUtil.decodeHexString(txHash), txBytes, TxBodyType.CONWAY);
        txIndex.put(txHash, memPoolTransaction);
        return memPoolTransaction;
    }

    @Override
    public synchronized MemPoolTransaction getNextTransaction() {
        var it = txIndex.entrySet().iterator();
        if (!it.hasNext()) return null;
        var entry = it.next();
        it.remove();
        return entry.getValue();
    }

    @Override
    public synchronized boolean isEmpty() {
        return txIndex.isEmpty();
    }

    @Override
    public synchronized int size() {
        return txIndex.size();
    }

    @Override
    public synchronized void clear() {
        txIndex.clear();
    }

    @Override
    public synchronized int removeByTxHashes(Set<String> txHashes) {
        int removed = 0;
        for (String hash : txHashes) {
            if (txIndex.remove(hash) != null) {
                removed++;
            }
        }
        return removed;
    }

    @Override
    public synchronized int evictOldest(int count) {
        int evicted = 0;
        var it = txIndex.entrySet().iterator();
        while (it.hasNext() && evicted < count) {
            it.next();
            it.remove();
            evicted++;
        }
        return evicted;
    }

    @Override
    public synchronized int removeOlderThan(long beforeEpochMillis) {
        int removed = 0;
        var it = txIndex.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (entry.getValue().insertedAt() < beforeEpochMillis) {
                it.remove();
                removed++;
            } else {
                // LinkedHashMap is insertion-ordered, so once we hit a newer entry, stop
                break;
            }
        }
        return removed;
    }
}
