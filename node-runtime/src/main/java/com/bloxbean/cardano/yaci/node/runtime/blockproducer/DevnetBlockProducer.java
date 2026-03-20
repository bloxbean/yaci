package com.bloxbean.cardano.yaci.node.runtime.blockproducer;

import com.bloxbean.cardano.yaci.core.network.server.NodeServer;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.node.api.utxo.UtxoState;
import com.bloxbean.cardano.yaci.node.runtime.chain.MemPool;
import lombok.extern.slf4j.Slf4j;

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
public class DevnetBlockProducer implements BlockProducerService {

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
    private volatile boolean forceSequentialSlots = false;

    public DevnetBlockProducer(ChainState chainState, MemPool memPool, NodeServer nodeServer,
                         EventBus eventBus, ScheduledExecutorService scheduler,
                         int blockTimeMillis, boolean lazy,
                         long genesisTimestamp, int slotLengthMillis,
                         GenesisConfig genesisConfig) {
        this(chainState, memPool, nodeServer, eventBus, scheduler, new DevnetBlockBuilder(),
                blockTimeMillis, lazy, genesisTimestamp, slotLengthMillis,
                genesisConfig, null, null);
    }

    public DevnetBlockProducer(ChainState chainState, MemPool memPool, NodeServer nodeServer,
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

    public DevnetBlockProducer(ChainState chainState, MemPool memPool, NodeServer nodeServer,
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
    /**
     * Enable or disable sequential slot mode.
     * When enabled, slots increment sequentially from lastUsedSlot instead of using wall-clock time.
     * Used in past-time-travel mode where blocks must start at slot 0.
     */
    public void setForceSequentialSlots(boolean force) {
        this.forceSequentialSlots = force;
    }

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
        return BlockProducerHelper.drainMempool(memPool, transactionValidatorService, utxoState);
    }

    private void storeBlock(DevnetBlockBuilder.BlockBuildResult result) {
        BlockProducerHelper.storeBlock(chainState, result);
    }

    private long calculateCurrentSlot() {
        if (forceSequentialSlots) {
            // Past-time-travel mode: produce blocks sequentially, don't jump to wall-clock
            lastUsedSlot = lastUsedSlot + 1;
            return lastUsedSlot;
        }
        long wallClockSlot = (System.currentTimeMillis() - genesisTimestamp) / slotLengthMillis;
        long slot = Math.max(wallClockSlot, lastUsedSlot + 1);
        lastUsedSlot = slot;
        return slot;
    }

    private void publishEvent(DevnetBlockBuilder.BlockBuildResult result, int txCount) {
        BlockProducerHelper.publishEvent(eventBus, result, txCount, "block-producer");
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
        BlockProducerHelper.notifyServer(nodeServer);
    }
}
