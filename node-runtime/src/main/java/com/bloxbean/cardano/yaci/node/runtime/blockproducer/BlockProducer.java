package com.bloxbean.cardano.yaci.node.runtime.blockproducer;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yaci.core.network.server.NodeServer;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.api.EventMetadata;
import com.bloxbean.cardano.yaci.events.api.PublishOptions;
import com.bloxbean.cardano.yaci.node.runtime.chain.MemPool;
import com.bloxbean.cardano.yaci.node.runtime.chain.MemPoolTransaction;
import com.bloxbean.cardano.yaci.node.runtime.events.BlockAppliedEvent;
import com.bloxbean.cardano.yaci.node.runtime.events.BlockProducedEvent;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Produces blocks for a standalone devnet node.
 * Drains transactions from the mempool and builds structurally valid Conway-era blocks
 * at a configured interval.
 */
@Slf4j
public class BlockProducer {

    private final ChainState chainState;
    private final MemPool memPool;
    private final NodeServer nodeServer;
    private final EventBus eventBus;
    private final ScheduledExecutorService scheduler;
    private final DevnetBlockBuilder blockBuilder;

    private final int blockTimeMillis;
    private final boolean lazy;
    private final long genesisTimestamp;
    private final int slotLengthMillis;
    private final GenesisConfig genesisConfig;

    private ScheduledFuture<?> scheduledTask;
    private long nextBlockNumber;
    private byte[] prevBlockHash;
    private volatile boolean running;

    public BlockProducer(ChainState chainState, MemPool memPool, NodeServer nodeServer,
                         EventBus eventBus, ScheduledExecutorService scheduler,
                         int blockTimeMillis, boolean lazy,
                         long genesisTimestamp, int slotLengthMillis,
                         GenesisConfig genesisConfig) {
        this.chainState = chainState;
        this.memPool = memPool;
        this.nodeServer = nodeServer;
        this.eventBus = eventBus;
        this.scheduler = scheduler;
        this.blockBuilder = new DevnetBlockBuilder();
        this.blockTimeMillis = blockTimeMillis;
        this.lazy = lazy;
        this.genesisTimestamp = genesisTimestamp > 0 ? genesisTimestamp : System.currentTimeMillis();
        this.slotLengthMillis = slotLengthMillis;
        this.genesisConfig = genesisConfig;
    }

    /**
     * Start block production. Checks existing chain state for restart scenario,
     * or produces a genesis block for fresh start.
     */
    public void start() {
        if (running) {
            log.warn("Block producer is already running");
            return;
        }

        // Check for existing tip (restart scenario)
        ChainTip existingTip = chainState.getTip();
        if (existingTip != null) {
            nextBlockNumber = existingTip.getBlockNumber() + 1;
            prevBlockHash = existingTip.getBlockHash();
            log.info("Block producer resuming from existing tip: block={}, slot={}",
                    existingTip.getBlockNumber(), existingTip.getSlot());
        } else {
            // Fresh start — produce genesis block
            produceGenesisBlock();
        }

        running = true;

        // Schedule periodic block production
        scheduledTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                produceBlock();
            } catch (Exception e) {
                log.error("Error producing block", e);
            }
        }, blockTimeMillis, blockTimeMillis, TimeUnit.MILLISECONDS);

        log.info("Block producer started: interval={}ms, lazy={}, slotLength={}ms",
                blockTimeMillis, lazy, slotLengthMillis);
    }

    /**
     * Stop block production.
     */
    public void stop() {
        running = false;
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
            scheduledTask = null;
        }
        log.info("Block producer stopped");
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * Produce a genesis block (block 0). If genesis funds are configured,
     * the block contains transactions distributing initial ADA.
     */
    private void produceGenesisBlock() {
        long slot = calculateCurrentSlot();

        List<byte[]> genesisTxs = List.of();
        if (genesisConfig != null && genesisConfig.hasInitialFunds()) {
            genesisTxs = GenesisTxBuilder.buildGenesisTransactions(genesisConfig.getInitialFunds());
            log.info("Genesis block will include {} transaction(s) distributing funds to {} addresses",
                    genesisTxs.size(), genesisConfig.getInitialFunds().size());
        }

        var result = blockBuilder.buildBlock(0, slot, null, genesisTxs);
        storeBlock(result);
        nextBlockNumber = 1;
        prevBlockHash = result.blockHash();

        log.info("Genesis block produced: slot={}, txs={}, hash={}",
                slot, genesisTxs.size(), HexUtil.encodeHexString(result.blockHash()));

        publishEvent(result, genesisTxs.size());
        notifyServer();
    }

    /**
     * Produce a block by draining the mempool.
     * In lazy mode, skips production when mempool is empty.
     */
    void produceBlock() {
        // Drain mempool
        List<byte[]> txList = drainMempool();

        // In lazy mode, skip if no transactions
        if (lazy && txList.isEmpty()) {
            return;
        }

        long slot = calculateCurrentSlot();
        var result = blockBuilder.buildBlock(nextBlockNumber, slot, prevBlockHash, txList);
        storeBlock(result);

        long producedBlockNumber = nextBlockNumber;
        nextBlockNumber++;
        prevBlockHash = result.blockHash();

        if (log.isDebugEnabled() || !txList.isEmpty()) {
            log.info("Block #{} produced: slot={}, txs={}, hash={}",
                    producedBlockNumber, slot, txList.size(),
                    HexUtil.encodeHexString(result.blockHash()));
        }

        publishEvent(result, txList.size());
        notifyServer();
    }

    private List<byte[]> drainMempool() {
        List<byte[]> txList = new ArrayList<>();
        while (!memPool.isEmpty()) {
            MemPoolTransaction mpt = memPool.getNextTransaction();
            if (mpt == null) break;
            txList.add(mpt.txBytes());
        }
        return txList;
    }

    /**
     * Store block in ChainState. storeBlock first (which also writes to blockHeaderStore),
     * then storeBlockHeader to overwrite with the correct wrapped header bytes.
     */
    private void storeBlock(DevnetBlockBuilder.BlockBuildResult result) {
        chainState.storeBlock(result.blockHash(), result.blockNumber(), result.slot(), result.blockCbor());
        chainState.storeBlockHeader(result.blockHash(), result.blockNumber(), result.slot(), result.wrappedHeaderCbor());
    }

    private long calculateCurrentSlot() {
        return (System.currentTimeMillis() - genesisTimestamp) / slotLengthMillis;
    }

    private void publishEvent(DevnetBlockBuilder.BlockBuildResult result, int txCount) {
        if (eventBus == null) return;

        String hashHex = HexUtil.encodeHexString(result.blockHash());
        EventMetadata meta = EventMetadata.builder()
                .origin("block-producer")
                .slot(result.slot())
                .blockNo(result.blockNumber())
                .blockHash(hashHex)
                .build();
        PublishOptions opts = PublishOptions.builder().build();

        try {
            // Publish BlockProducedEvent (lightweight, no parsed block)
            eventBus.publish(
                    new BlockProducedEvent(7, result.slot(), result.blockNumber(),
                            result.blockHash(), txCount),
                    meta, opts);

            // Publish BlockAppliedEvent so UTXO store and other listeners can process the block
            Block block = BlockSerializer.INSTANCE.deserialize(result.blockCbor());
            eventBus.publish(
                    new BlockAppliedEvent(Era.Conway, result.slot(), result.blockNumber(),
                            hashHex, block),
                    meta, opts);
        } catch (Exception e) {
            log.debug("Failed to publish block events: {}", e.getMessage());
        }
    }

    private void notifyServer() {
        if (nodeServer != null) {
            try {
                nodeServer.notifyNewDataAvailable();
            } catch (Exception e) {
                log.warn("Failed to notify server of new block: {}", e.getMessage());
            }
        }
    }
}
