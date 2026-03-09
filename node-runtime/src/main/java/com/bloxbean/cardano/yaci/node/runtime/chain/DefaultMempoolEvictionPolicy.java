package com.bloxbean.cardano.yaci.node.runtime.chain;

import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.node.api.events.BlockAppliedEvent;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Default mempool eviction policy with three triggers:
 * 1. Block confirmation — remove txs that appear in a confirmed block
 * 2. TTL expiry — remove txs older than maxAgeMillis
 * 3. Max size cap — evict oldest when mempool exceeds maxSize
 */
@Slf4j
public class DefaultMempoolEvictionPolicy implements MempoolEvictionPolicy {

    private final MemPool memPool;
    private final long maxAgeMillis;
    private final int maxSize;

    public DefaultMempoolEvictionPolicy(MemPool memPool, long maxAgeMillis, int maxSize) {
        this.memPool = memPool;
        this.maxAgeMillis = maxAgeMillis;
        this.maxSize = maxSize;
    }

    @Override
    public void onBlockApplied(BlockAppliedEvent event) {
        if (event.block() == null || event.block().getTransactionBodies() == null) {
            return;
        }

        Set<String> confirmedHashes = event.block().getTransactionBodies().stream()
                .map(TransactionBody::getTxHash)
                .collect(Collectors.toSet());

        if (confirmedHashes.isEmpty()) {
            return;
        }

        int removed = memPool.removeByTxHashes(confirmedHashes);
        if (removed > 0) {
            log.info("Evicted {} confirmed txs from mempool (block #{})", removed, event.blockNumber());
        }
    }

    @Override
    public void onPeriodicCheck() {
        // TTL expiry
        if (maxAgeMillis > 0) {
            long cutoff = System.currentTimeMillis() - maxAgeMillis;
            int expired = memPool.removeOlderThan(cutoff);
            if (expired > 0) {
                log.info("Evicted {} expired txs from mempool (age > {}ms)", expired, maxAgeMillis);
            }
        }

        // Max size cap
        if (maxSize > 0) {
            int excess = memPool.size() - maxSize;
            if (excess > 0) {
                int evicted = memPool.evictOldest(excess);
                log.info("Evicted {} txs from mempool (exceeded max size {})", evicted, maxSize);
            }
        }
    }
}
