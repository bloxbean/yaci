package com.bloxbean.cardano.yaci.node.runtime.chain;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.yaci.core.common.TxBodyType;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicLong;

public class DefaultMemPool implements MemPool {
    private final Queue<MemPoolTransaction> memPool;
    private final AtomicLong cursor = new AtomicLong(0);

    public DefaultMemPool() {
        this.memPool = new LinkedList<>();
    }

    @Override
    public synchronized void addTransaction(byte[] txBytes) {
        var txHash = TransactionUtil.getTxHash(txBytes);
        long txSeqId = cursor.incrementAndGet();
        var memPoolTransaction = new MemPoolTransaction(txSeqId, HexUtil.decodeHexString(txHash), txBytes, TxBodyType.CONWAY);
        memPool.offer(memPoolTransaction);
    }

    @Override
    public synchronized MemPoolTransaction getNextTransaction() {
        return memPool.poll(); // returns null if the queue is empty
    }

    @Override
    public synchronized boolean isEmpty() {
        return memPool.isEmpty();
    }

    @Override
    public synchronized int size() {
        return memPool.size();
    }

    @Override
    public synchronized void clear() {
        memPool.clear();
    }

}
