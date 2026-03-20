package com.bloxbean.cardano.yaci.node.runtime.blockproducer;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.crypto.kes.OpCert;
import com.bloxbean.cardano.yaci.core.network.server.NodeServer;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.node.api.utxo.UtxoState;
import com.bloxbean.cardano.yaci.node.runtime.chain.MemPool;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Slot leader block producer for public networks (preview, preprod, mainnet).
 * Checks each slot for Ouroboros Praos leader eligibility based on the pool's relative stake,
 * and produces a block only when elected.
 */
@Slf4j
public class SlotLeaderBlockProducer implements BlockProducerService {

    private static final MathContext MC = new MathContext(40);
    private static final long SYNC_TOLERANCE_SLOTS = 10;

    private final ChainState chainState;
    private final MemPool memPool;
    private final NodeServer nodeServer;
    private final EventBus eventBus;
    private final ScheduledExecutorService scheduler;
    private final SignedBlockBuilder blockBuilder;
    private final EpochNonceState epochNonceState;
    private final SlotLeaderCheck slotLeaderCheck;
    private final StakeDataProvider stakeDataProvider;
    private final String poolHash;
    private final long genesisTimestamp;
    private final int slotLengthMillis;

    // Optional: transaction validator for mempool draining
    private final TransactionValidationService transactionValidatorService;
    private final UtxoState utxoState;

    // Runtime state
    private ScheduledFuture<?> scheduledTask;
    private volatile boolean running;
    private long lastCheckedSlot = -1;
    private int lastStakeEpoch = -1;
    private int lastStakeAttemptEpoch = -1; // tracks failed attempts to avoid per-slot retries
    private BigDecimal sigma = BigDecimal.ZERO;

    public SlotLeaderBlockProducer(
            ChainState chainState, MemPool memPool, NodeServer nodeServer,
            EventBus eventBus, ScheduledExecutorService scheduler,
            SignedBlockBuilder blockBuilder, EpochNonceState epochNonceState,
            SlotLeaderCheck slotLeaderCheck, StakeDataProvider stakeDataProvider,
            String poolHash, long genesisTimestamp, int slotLengthMillis,
            TransactionValidationService transactionValidatorService, UtxoState utxoState) {
        this.chainState = chainState;
        this.memPool = memPool;
        this.nodeServer = nodeServer;
        this.eventBus = eventBus;
        this.scheduler = scheduler;
        this.blockBuilder = blockBuilder;
        this.epochNonceState = epochNonceState;
        this.slotLeaderCheck = slotLeaderCheck;
        this.stakeDataProvider = stakeDataProvider;
        this.poolHash = poolHash;
        this.genesisTimestamp = genesisTimestamp;
        this.slotLengthMillis = slotLengthMillis;
        this.transactionValidatorService = transactionValidatorService;
        this.utxoState = utxoState;
    }

    @Override
    public void start() {
        if (running) {
            log.warn("SlotLeaderBlockProducer is already running");
            return;
        }
        running = true;

        // Schedule at slot-length interval (derived from genesis, not hardcoded)
        scheduledTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                checkAndProduceBlock();
            } catch (Exception e) {
                log.error("Error in slot leader check", e);
            }
        }, slotLengthMillis, slotLengthMillis, TimeUnit.MILLISECONDS);

        log.info("SlotLeaderBlockProducer started: poolHash={}, slotLength={}ms",
                poolHash, slotLengthMillis);
    }

    @Override
    public void stop() {
        running = false;
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
            scheduledTask = null;
        }
        log.info("SlotLeaderBlockProducer stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void resetToChainTip() {
        ChainTip tip = chainState.getTip();
        if (tip != null) {
            lastCheckedSlot = tip.getSlot();
            log.info("SlotLeaderBlockProducer reset to chain tip: slot={}", tip.getSlot());
        }
    }

    void checkAndProduceBlock() {
        // 1. Calculate current wall-clock slot
        long currentSlot = (System.currentTimeMillis() - genesisTimestamp) / slotLengthMillis;

        // 2. Skip if already checked this slot
        if (currentSlot <= lastCheckedSlot) {
            return;
        }
        lastCheckedSlot = currentSlot;

        // 3. Check sync readiness: are we close enough to tip?
        ChainTip tip = chainState.getTip();
        if (tip == null) {
            return; // No chain state yet
        }
        long slotsBehind = currentSlot - tip.getSlot();
        if (slotsBehind > SYNC_TOLERANCE_SLOTS) {
            if (currentSlot % 100 == 0) { // Log periodically, not every slot
                log.debug("Not synced to tip (behind by {} slots), skipping leader check", slotsBehind);
            }
            return;
        }

        // 4. Refresh stake data on epoch boundary
        int currentEpoch = epochNonceState.epochForSlot(currentSlot);
        refreshStakeData(currentEpoch);

        if (sigma.signum() == 0) {
            if (currentSlot % 100 == 0) {
                log.debug("No stake data available for epoch {}, skipping leader check", currentEpoch);
            }
            return;
        }

        // 5. Get epoch nonce
        byte[] epochNonce = epochNonceState.getEpochNonce();
        if (epochNonce == null) {
            log.warn("Epoch nonce not available, skipping leader check for slot {}", currentSlot);
            return;
        }

        // 6. Check leader eligibility
        BlockSigner.VrfSignResult vrfResult = slotLeaderCheck.checkAndProve(currentSlot, epochNonce, sigma);
        if (vrfResult == null) {
            return; // Not a leader for this slot
        }

        log.info("SLOT LEADER! Elected for slot {} (epoch {})", currentSlot, currentEpoch);

        // 7. Produce block
        try {
            produceBlock(currentSlot, vrfResult, tip);
        } catch (Exception e) {
            log.error("Failed to produce block for slot {}", currentSlot, e);
        }
    }

    private void refreshStakeData(int epoch) {
        if (epoch == lastStakeEpoch) {
            return; // Already have fresh data for this epoch
        }
        if (epoch == lastStakeAttemptEpoch) {
            return; // Already attempted this epoch and failed — wait for next epoch
        }

        lastStakeAttemptEpoch = epoch;

        BigInteger poolStake = stakeDataProvider.getPoolStake(poolHash, epoch);
        BigInteger totalStake = stakeDataProvider.getTotalStake(epoch);

        if (poolStake == null || totalStake == null || totalStake.signum() == 0) {
            log.info("Stake data not available for pool {} in epoch {} (poolStake={}, totalStake={})",
                    poolHash, epoch, poolStake, totalStake);
            if (lastStakeEpoch >= 0 && lastStakeEpoch != epoch) {
                log.warn("Using stale sigma from epoch {} for epoch {}", lastStakeEpoch, epoch);
            }
            return;
        }

        sigma = new BigDecimal(poolStake).divide(new BigDecimal(totalStake), MC);
        lastStakeEpoch = epoch;

        log.info("Stake data refreshed for epoch {}: poolStake={}, totalStake={}, sigma={}",
                epoch, poolStake, totalStake, sigma);
    }

    private void produceBlock(long slot, BlockSigner.VrfSignResult vrfResult, ChainTip tip) {
        long blockNumber = tip.getBlockNumber() + 1;
        byte[] prevHash = tip.getBlockHash();

        List<byte[]> txList = BlockProducerHelper.drainMempool(
                memPool, transactionValidatorService, utxoState);

        var result = blockBuilder.buildBlock(blockNumber, slot, prevHash, txList, vrfResult);

        BlockProducerHelper.storeBlock(chainState, result);

        log.info("Block #{} produced: slot={}, txs={}, hash={}",
                blockNumber, slot, txList.size(), HexUtil.encodeHexString(result.blockHash()));

        BlockProducerHelper.publishEvent(eventBus, result, txList.size(), "slot-leader-block-producer");
        BlockProducerHelper.notifyServer(nodeServer);
    }

    /**
     * Derive the pool hash from an operational certificate.
     * Pool hash = blake2b_224(coldVkey).
     *
     * @param opCert the operational certificate
     * @return hex-encoded pool hash (28 bytes / 56 hex chars)
     */
    public static String derivePoolHash(OpCert opCert) {
        byte[] coldVkey = opCert.getColdVkey();
        byte[] hash = Blake2bUtil.blake2bHash224(coldVkey);
        return HexUtil.encodeHexString(hash);
    }
}
