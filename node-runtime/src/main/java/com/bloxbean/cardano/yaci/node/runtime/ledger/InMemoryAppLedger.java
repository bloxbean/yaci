package com.bloxbean.cardano.yaci.node.runtime.ledger;

import com.bloxbean.cardano.yaci.node.api.consensus.ConsensusProof;
import com.bloxbean.cardano.yaci.node.api.ledger.AppBlock;
import com.bloxbean.cardano.yaci.node.api.ledger.AppLedger;
import com.bloxbean.cardano.yaci.node.api.ledger.AppLedgerTip;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of AppLedger for testing.
 */
public class InMemoryAppLedger implements AppLedger {

    // topicId -> (blockNumber -> AppBlock)
    private final Map<String, TreeMap<Long, AppBlock>> blocks = new ConcurrentHashMap<>();
    // topicId:blockNumber -> ConsensusProof
    private final Map<String, ConsensusProof> proofs = new ConcurrentHashMap<>();

    @Override
    public void storeBlock(AppBlock block) {
        blocks.computeIfAbsent(block.getTopicId(), k -> new TreeMap<>())
                .put(block.getBlockNumber(), block);
    }

    @Override
    public Optional<AppBlock> getBlock(String topicId, long blockNumber) {
        TreeMap<Long, AppBlock> topicBlocks = blocks.get(topicId);
        if (topicBlocks == null) return Optional.empty();
        return Optional.ofNullable(topicBlocks.get(blockNumber));
    }

    @Override
    public Optional<AppBlock> getLatestBlock(String topicId) {
        TreeMap<Long, AppBlock> topicBlocks = blocks.get(topicId);
        if (topicBlocks == null || topicBlocks.isEmpty()) return Optional.empty();
        return Optional.of(topicBlocks.lastEntry().getValue());
    }

    @Override
    public Optional<AppLedgerTip> getTip(String topicId) {
        return getLatestBlock(topicId).map(block -> {
            long totalMessages = blocks.get(topicId).values().stream()
                    .mapToLong(AppBlock::messageCount)
                    .sum();
            return AppLedgerTip.builder()
                    .topicId(topicId)
                    .blockNumber(block.getBlockNumber())
                    .blockHash(block.getBlockHash())
                    .timestamp(block.getTimestamp())
                    .totalMessages(totalMessages)
                    .build();
        });
    }

    @Override
    public List<AppBlock> getBlocks(String topicId, long fromBlock, long toBlock) {
        TreeMap<Long, AppBlock> topicBlocks = blocks.get(topicId);
        if (topicBlocks == null) return List.of();
        return new ArrayList<>(topicBlocks.subMap(fromBlock, true, toBlock, true).values());
    }

    @Override
    public void storeConsensusProof(String topicId, long blockNumber, ConsensusProof proof) {
        proofs.put(topicId + ":" + blockNumber, proof);
    }

    @Override
    public Optional<ConsensusProof> getConsensusProof(String topicId, long blockNumber) {
        return Optional.ofNullable(proofs.get(topicId + ":" + blockNumber));
    }

    @Override
    public void close() {
        blocks.clear();
        proofs.clear();
    }
}
