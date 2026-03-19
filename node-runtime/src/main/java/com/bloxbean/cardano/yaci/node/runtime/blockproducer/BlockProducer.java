package com.bloxbean.cardano.yaci.node.runtime.blockproducer;

import com.bloxbean.cardano.yaci.node.ledgerrules.ValidationResult;
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
import com.bloxbean.cardano.yaci.node.api.utxo.UtxoState;
import com.bloxbean.cardano.yaci.node.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yaci.node.api.events.BlockProducedEvent;
import com.bloxbean.cardano.yaci.node.api.model.MemPoolTransaction;
import com.bloxbean.cardano.yaci.node.runtime.chain.MemPool;
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

    // Optional: transaction evaluator for block-time validation
    private final TransactionValidationService transactionValidatorService;
    private final UtxoState utxoState;

    private ScheduledFuture<?> scheduledTask;
    private long nextBlockNumber;
    private byte[] prevBlockHash;
    private long lastUsedSlot = -1;
    private volatile boolean running;

    public BlockProducer(ChainState chainState, MemPool memPool, NodeServer nodeServer,
                         EventBus eventBus, ScheduledExecutorService scheduler,
                         int blockTimeMillis, boolean lazy,
                         long genesisTimestamp, int slotLengthMillis,
                         GenesisConfig genesisConfig) {
        this(chainState, memPool, nodeServer, eventBus, scheduler, new DevnetBlockBuilder(),
                blockTimeMillis, lazy, genesisTimestamp, slotLengthMillis,
                genesisConfig, null, null);
    }

    public BlockProducer(ChainState chainState, MemPool memPool, NodeServer nodeServer,
                         EventBus eventBus, ScheduledExecutorService scheduler,
                         int blockTimeMillis, boolean lazy,
                         long genesisTimestamp, int slotLengthMillis,
                         GenesisConfig genesisConfig,
                         TransactionValidationService transactionValidatorService,
                         UtxoState utxoState) {
        this(chainState, memPool, nodeServer, eventBus, scheduler, new DevnetBlockBuilder(),
                blockTimeMillis, lazy, genesisTimestamp, slotLengthMillis,
                genesisConfig, transactionValidatorService, utxoState);
    }

    public BlockProducer(ChainState chainState, MemPool memPool, NodeServer nodeServer,
                         EventBus eventBus, ScheduledExecutorService scheduler,
                         DevnetBlockBuilder blockBuilder,
                         int blockTimeMillis, boolean lazy,
                         long genesisTimestamp, int slotLengthMillis,
                         GenesisConfig genesisConfig,
                         TransactionValidationService transactionValidatorService,
                         UtxoState utxoState) {
        this.chainState = chainState;
        this.memPool = memPool;
        this.nodeServer = nodeServer;
        this.eventBus = eventBus;
        this.scheduler = scheduler;
        this.blockBuilder = blockBuilder;
        this.blockTimeMillis = blockTimeMillis;
        this.lazy = lazy;
        if (genesisTimestamp <= 0) {
            throw new IllegalArgumentException("genesisTimestamp must be > 0, got: " + genesisTimestamp);
        }
        this.genesisTimestamp = genesisTimestamp;
        this.slotLengthMillis = slotLengthMillis;
        this.genesisConfig = genesisConfig;
        this.transactionValidatorService = transactionValidatorService;
        this.utxoState = utxoState;
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
            lastUsedSlot = existingTip.getSlot();
            log.info("Block producer resuming from existing tip: block={}, slot={}",
                    existingTip.getBlockNumber(), existingTip.getSlot());
        } else {
            // Fresh start — produce genesis block
            produceGenesisBlock();
        }

        running = true;

        // Schedule periodic block production
        scheduledTask = scheduler.scheduleWithFixedDelay(() -> {
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
     * Reset block producer state to resume from the current chain tip.
     * Called after an external rollback to sync nextBlockNumber/prevBlockHash.
     */
    public void resetToChainTip() {
        ChainTip tip = chainState.getTip();
        if (tip != null) {
            this.nextBlockNumber = tip.getBlockNumber() + 1;
            this.prevBlockHash = tip.getBlockHash();
            this.lastUsedSlot = tip.getSlot();
            log.info("Block producer reset to chain tip: block={}, slot={}",
                    tip.getBlockNumber(), tip.getSlot());
        }
    }

    /**
     * Produce an empty genesis block (block 0).
     * Genesis UTXOs are NOT embedded as transactions; they are stored directly
     * in the UTXO store using tx_hash = blake2b(address) to match the Cardano
     * protocol convention used by yaci-store and wallets.
     */
    private void produceGenesisBlock() {
        long slot = calculateCurrentSlot();

        if (genesisConfig != null && genesisConfig.hasInitialFunds()) {
            log.info("Genesis funds ({} addresses) will be stored directly in UTXO store (not embedded in block)",
                    genesisConfig.getInitialFunds().size());
        }

        var result = blockBuilder.buildBlock(0, slot, null, List.of());
        storeBlock(result);
        nextBlockNumber = 1;
        prevBlockHash = result.blockHash();

        log.info("Genesis block produced: slot={}, hash={}",
                slot, HexUtil.encodeHexString(result.blockHash()));

        publishEvent(result, 0);
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

        log.info("Block #{} produced: slot={}, txs={}",
                producedBlockNumber, slot, txList.size());

        publishEvent(result, txList.size());
        notifyServer();
    }

    private List<byte[]> drainMempool() {
        // Validate each tx with spent-tracking overlay
        BlockBuildUtxoOverlay overlay = new BlockBuildUtxoOverlay(utxoState);
        List<byte[]> txList = new ArrayList<>();
        while (!memPool.isEmpty()) {
            MemPoolTransaction mpt = memPool.getNextTransaction();
            if (mpt == null) break;

            ValidationResult result = transactionValidatorService.validate(
                    mpt.txBytes(), overlay.resolver());
            if (result.valid()) {
                txList.add(mpt.txBytes());
                overlay.markSpent(mpt.txBytes());
            } else {
                String txHashHex = HexUtil.encodeHexString(mpt.txHash());
                String errorMsg = result.firstErrorMessage("unknown error");
                log.warn("Dropping invalid tx {} during block production: {}", txHashHex, errorMsg);
            }
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
        long wallClockSlot = (System.currentTimeMillis() - genesisTimestamp) / slotLengthMillis;
        long slot = Math.max(wallClockSlot, lastUsedSlot + 1);
        lastUsedSlot = slot;
        return slot;
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
                    new BlockProducedEvent(6, result.slot(), result.blockNumber(),
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

    /**
     * Produce empty blocks rapidly from the current tip slot up to (and including) targetSlot.
     * Each block advances by one slot step. Used for time/slot advance in devnet mode.
     *
     * @param targetSlot the slot to advance to
     * @return number of blocks produced
     */
    public int produceEmptyBlocksToSlot(long targetSlot) {
        if (targetSlot <= lastUsedSlot) {
            log.warn("Target slot {} is not ahead of last used slot {}", targetSlot, lastUsedSlot);
            return 0;
        }

        int blocksProduced = 0;
        long currentSlot = lastUsedSlot + 1;

        while (currentSlot <= targetSlot) {
            var result = blockBuilder.buildBlock(nextBlockNumber, currentSlot, prevBlockHash, List.of());
            storeBlock(result);

            long producedBlockNumber = nextBlockNumber;
            nextBlockNumber++;
            prevBlockHash = result.blockHash();
            lastUsedSlot = currentSlot;
            blocksProduced++;

            publishEvent(result, 0);

            if (blocksProduced % 1000 == 0) {
                log.info("Time advance progress: {} blocks produced, current slot={}", blocksProduced, currentSlot);
            }

            currentSlot++;
        }

        // Notify server once at the end
        notifyServer();

        log.info("Time advance complete: {} empty blocks produced, new tip slot={}, block={}",
                blocksProduced, lastUsedSlot, nextBlockNumber - 1);

        return blocksProduced;
    }

    public int getSlotLengthMillis() {
        return slotLengthMillis;
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
