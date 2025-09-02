package com.bloxbean.cardano.yaci.node.runtime;

import com.bloxbean.cardano.yaci.core.exception.BlockParseRuntimeException;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbBlock;
import com.bloxbean.cardano.yaci.core.model.byron.ByronMainBlock;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.helper.PeerClient;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import com.bloxbean.cardano.yaci.helper.model.Transaction;
import com.bloxbean.cardano.yaci.node.api.SyncPhase;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * BodyFetchManager handles gap detection and range-based body fetching to complement HeaderSyncManager.
 *
 * This manager monitors the gap between header_tip (latest header) and tip (latest complete block)
 * and automatically fetches missing block bodies using range requests via PeerClient.fetch().
 *
 * Key Features:
 * - Continuous gap monitoring every 500ms
 * - Range-based fetching (up to 100 blocks per batch)
 * - Automatic pause/resume for rollback scenarios
 * - Integration with existing ChainState storage
 * - Virtual thread-based execution for lightweight concurrency
 *
 * This enables true parallel pipeline architecture where headers sync ahead of bodies.
 */
@Slf4j
public class BodyFetchManager implements BlockChainDataListener, Runnable {

    private final PeerClient peerClient;
    private final ChainState chainState;

    // Configuration
    private final long gapThreshold;
    private final int maxBatchSize;
    private final long monitoringIntervalMs;
    private final long tipProximityThreshold;

    // State management
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private volatile Thread monitoringThread;
    private volatile SyncPhase syncPhase = SyncPhase.INITIAL_SYNC;

    // Metrics
    private final AtomicInteger bodiesReceived = new AtomicInteger(0);
    private final AtomicInteger batchesCompleted = new AtomicInteger(0);
    private final AtomicLong lastGapSize = new AtomicLong(0);
    private final AtomicLong totalBlocksFetched = new AtomicLong(0);
    private volatile long startTime;

    // Current batch tracking
    private volatile boolean batchInProgress = false;
    private volatile Point currentBatchFrom;
    private volatile Point currentBatchTo;
    private volatile int currentBatchSize;

    // Rollback tracking to prevent storing stale blocks
    private volatile Point lastRollbackPoint = null;

    // Runtime recovery guardrails
    private final AtomicInteger consecutiveStaleBlocks = new AtomicInteger(0);
    private final AtomicBoolean recoveryInProgress = new AtomicBoolean(false);
    private static final int STALE_RECOVERY_THRESHOLD = 20; // consecutive stale drops before probing for corruption

    /**
     * Create BodyFetchManager with default configuration.
     */
    public BodyFetchManager(PeerClient peerClient, ChainState chainState) {
        this(peerClient, chainState, 10, 100, 500, 10);
    }

    /**
     * Create BodyFetchManager with custom configuration.
     *
     * @param peerClient The PeerClient for fetching block ranges
     * @param chainState The ChainState for storage operations
     * @param gapThreshold Minimum gap size to trigger fetching (default: 10 blocks)
     * @param maxBatchSize Maximum blocks per range request (default: 100)
     * @param monitoringIntervalMs Gap monitoring frequency (default: 500ms)
     * @param tipProximityThreshold Maximum gap to consider "at tip" for immediate resume (default: 10 slots)
     */
    public BodyFetchManager(PeerClient peerClient, ChainState chainState,
                           long gapThreshold, int maxBatchSize, long monitoringIntervalMs, long tipProximityThreshold) {
        if (peerClient == null) {
            throw new IllegalArgumentException("PeerClient cannot be null");
        }
        if (chainState == null) {
            throw new IllegalArgumentException("ChainState cannot be null");
        }
        if (gapThreshold < 1) {
            throw new IllegalArgumentException("Gap threshold must be positive: " + gapThreshold);
        }
        if (maxBatchSize < 1) {
            throw new IllegalArgumentException("Max batch size must be positive: " + maxBatchSize);
        }
        if (monitoringIntervalMs < 1) {
            throw new IllegalArgumentException("Monitoring interval must be positive: " + monitoringIntervalMs);
        }

        this.peerClient = peerClient;
        this.chainState = chainState;
        this.gapThreshold = gapThreshold;
        this.maxBatchSize = maxBatchSize;
        this.monitoringIntervalMs = monitoringIntervalMs;
        this.tipProximityThreshold = tipProximityThreshold;

        if (log.isInfoEnabled()) {
            log.info("🏗️ BodyFetchManager created with config: gapThreshold={}, maxBatchSize={}, monitoringInterval={}ms, tipProximityThreshold={}",
                    gapThreshold, maxBatchSize, monitoringIntervalMs, tipProximityThreshold);
        }
    }

    /**
     * Start the body fetch manager in a virtual thread.
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("BodyFetchManager is already running");
            return;
        }

        startTime = System.currentTimeMillis();
        resetMetrics();

        // Check if we should immediately resume (when already near tip)
        checkForImmediateResume();

        // Use virtual thread for lightweight concurrency
        monitoringThread = Thread.ofVirtual()
            .name("BodyFetchManager-Monitor")
            .start(this);

        if (log.isInfoEnabled()) {
            log.info("🚀 BodyFetchManager started with monitoring thread: {}", monitoringThread.getName());
        }
    }

    /**
     * Stop the body fetch manager.
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            log.warn("BodyFetchManager is not running");
            return;
        }

        if (monitoringThread != null) {
            monitoringThread.interrupt();
        }

        if (log.isInfoEnabled()) {
            log.info("🛑 BodyFetchManager stopped after running for {}ms",
                    System.currentTimeMillis() - startTime);
        }
    }

    /**
     * Pause body fetching (used during rollback scenarios).
     */
    public void pause() {
        paused.set(true);
        if (log.isDebugEnabled()) {
            log.debug("⏸️ BodyFetchManager paused");
        }
    }

    /**
     * Resume body fetching after pause.
     */
    public void resume() {
        paused.set(false);
        if (log.isDebugEnabled()) {
            log.debug("▶️ BodyFetchManager resumed");
        }
    }

    /**
     * Main monitoring loop - runs continuously checking for* Uses adaptive monitoring interval based on sync phase:
     * - STEADY_STATE (tip): 100ms for immediate response
     * - INITIAL_SYNC (bulk): 500ms for efficiency
     */
    @Override
    public void run() {
        log.info("📊 BodyFetchManager monitoring thread started");

        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                if (!paused.get()) {
                    checkAndFetchBodies();
                }

                // Adaptive monitoring interval based on sync phase
                long currentInterval = getAdaptiveMonitoringInterval();
                Thread.sleep(currentInterval);

            } catch (InterruptedException e) {
                log.info("BodyFetchManager monitoring thread interrupted");
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error in BodyFetchManager monitoring loop", e);
                // Continue running despite errors
            }
        }

        log.info("📊 BodyFetchManager monitoring thread stopped");
    }

    /**
     * Get adaptive monitoring interval based on sync phase.
     * At tip: faster monitoring for immediate response
     * During bulk: slower monitoring for efficiency
     */
    private long getAdaptiveMonitoringInterval() {
        if (syncPhase == SyncPhase.STEADY_STATE) {
            return 100; // 100ms at tip for immediate body fetching
        }
        return monitoringIntervalMs; // Use configured interval for bulk sync
    }

    /**
     * Check gap and fetch bodies if needed.
     */
    private void checkAndFetchBodies() {
        if (!peerClient.isRunning()) {
            if (log.isTraceEnabled()) {
                log.trace("PeerClient not running, skipping gap check");
            }
            return;
        }

        if (batchInProgress) {
            if (log.isTraceEnabled()) {
                log.trace("Batch in progress, skipping new fetch");
            }
            return;
        }

        long gapSize = calculateGapSize();
        lastGapSize.set(gapSize);

        // Debug logging to understand gap detection
        ChainTip headerTip = chainState.getHeaderTip();
        ChainTip tip = chainState.getTip();

        if (log.isDebugEnabled()) {
            log.debug("🔍 Gap check: headerTip={}, tip={}, gapSize={}, threshold={}",
                    headerTip != null ? "slot=" + headerTip.getSlot() + " block#" + headerTip.getBlockNumber() : "null",
                    tip != null ? "slot=" + tip.getSlot() + " block#" + tip.getBlockNumber() : "null",
                    gapSize, gapThreshold);
        }

        if (shouldFetchBodies(gapSize)) {
            if (log.isDebugEnabled())
                log.debug("📈 Gap detected: {} slots >= threshold {}, triggering body fetch", gapSize, gapThreshold);

            BlockRange range = calculateNextRange();
            if (range != null) {
                fetchBlockRange(range);
            } else {
                log.warn("🚫 No valid range calculated despite gap of {} slots", gapSize);
            }
        }
    }

    /**
     * Calculate gap size between header_tip and tip.
     */
    private long calculateGapSize() {
        ChainTip headerTip = chainState.getHeaderTip();
        ChainTip tip = chainState.getTip();

        if (headerTip == null) {
            return 0; // No headers yet
        }

        if (tip == null) {
            return headerTip.getSlot(); // All headers are ahead
        }

        return headerTip.getSlot() - tip.getSlot();
    }

    /**
     * Determine if bodies should be fetched based on gap size and sync phase.
     *
     * STEADY_STATE (tip sync): Immediate body fetching (gap >= 1 slot)
     * INITIAL_SYNC (bulk): Efficient batching (gap >= configured threshold)
     */
    private boolean shouldFetchBodies(long gapSize) {
        // At tip: fetch immediately when any header is ahead
        if (syncPhase == SyncPhase.STEADY_STATE) {
            return gapSize >= 1; // Immediate body fetching at tip
        }

        // During bulk sync: use configured threshold for efficient batching
        return gapSize >= gapThreshold;
    }

    /**
     * Calculate the next range to fetch.
     */
    private BlockRange calculateNextRange() {
        ChainTip tip = chainState.getTip();
        ChainTip headerTip = chainState.getHeaderTip();

        if (headerTip == null) {
            log.warn("No header tip available for range calculation");
            return null;
        }

        // Start from tip + 1 if tip exists, otherwise from first available header
        Point fromPoint;
        if (tip == null) {
            // When starting fresh, find the first available header to start body fetching
            // This handles genesis sync where headers are available but no bodies yet
            log.debug("No body tip yet - looking for first available header to start body fetch");

            // Get the first block/header from chainstate
            // This now works for all networks including mainnet (Byron block 1 at slot 0)
            Point firstHeader = chainState.getFirstBlock();
            if (firstHeader == null) {
                log.debug("No headers available yet - waiting for headers before starting body fetch");
                return null;
            }

            log.debug("Found first header at slot={}, hash={} - starting body fetch from there",
                     firstHeader.getSlot(), firstHeader.getHash());
            fromPoint = firstHeader;
        } else {
            // Find next header after current body tip
            Point currentTipPoint = new Point(tip.getSlot(), HexUtil.encodeHexString(tip.getBlockHash()));
            log.debug("Looking for next header after body tip: slot={}, hash={}",
                     currentTipPoint.getSlot(), currentTipPoint.getHash());

            // Use the new findNextBlockHeader method that looks for headers beyond the body tip
            Point nextPoint = chainState.findNextBlockHeader(currentTipPoint);

            if (nextPoint == null) {
                log.warn("❌ No next header found after body tip: slot={}, hash={}. Headers may not be available yet.",
                        currentTipPoint.getSlot(), currentTipPoint.getHash());
                return null;
            }
            log.debug("Found next header: slot={}, hash={}", nextPoint.getSlot(), nextPoint.getHash());
            fromPoint = nextPoint;
        }

        // Find the end point by getting the last point after maxBatchSize blocks
        // This automatically handles the fact that not every slot has a block in Cardano
        Point toPoint = chainState.findLastPointAfterNBlocks(fromPoint, maxBatchSize);
        if (toPoint == null) {
            log.warn("No valid end point found for range starting from slot {}", fromPoint.getSlot());
            return null;
        }

        // The range size is based on the actual number of blocks found, not slot difference
        // Since findLastPointAfterNBlocks returns after maxBatchSize blocks, the size is at most maxBatchSize
        // The server will return what's available within this range
        int rangeSize = maxBatchSize;

        if (log.isDebugEnabled()) {
            log.debug("📦 Calculated range: from={}, to={}, size={}",
                     fromPoint.getSlot(), toPoint.getSlot(), rangeSize);
        }

        return new BlockRange(fromPoint, toPoint, rangeSize);
    }

    /**
     * Fetch a block range using PeerClient.
     */
    private void fetchBlockRange(BlockRange range) {
        if (batchInProgress) {
            log.warn("Batch already in progress, skipping new fetch");
            return;
        }

        batchInProgress = true;
        currentBatchFrom = range.from;
        currentBatchTo = range.to;
        currentBatchSize = range.size;

        if (log.isDebugEnabled()) {
            log.debug("🔄 Fetching block range: from slot {} to slot {} ({} blocks)",
                    range.from.getSlot(), range.to.getSlot(), range.size);
        }

        try {
            peerClient.fetch(range.from, range.to);
        } catch (Exception e) {
            log.error("Failed to fetch block range: from={}, to={}", range.from, range.to, e);
            batchInProgress = false; // Reset on error
        }
    }

    // ================================================================
    // BlockChainDataListener Implementation
    // ================================================================

    @Override
    public void onBlock(Era era, Block block, List<Transaction> transactions) {
        // Store complete block and update tip
        if (block == null || block.getHeader() == null || block.getHeader().getHeaderBody() == null) {
            log.warn("Received null or incomplete block, skipping storage");
            return;
        }

        try {
            long slot = block.getHeader().getHeaderBody().getSlot();
            long blockNumber = block.getHeader().getHeaderBody().getBlockNumber();
            String hash = block.getHeader().getHeaderBody().getBlockHash();

            // Check for stale blocks that arrived after rollback
            if (isStaleBlock(blockNumber, slot, hash, false)) {
                log.warn("🗑️ DISCARDED STALE BLOCK: Block #{} at slot {} arrived after rollback - skipping storage",
                        blockNumber, slot);
                onStaleBlockObserved();
                return;
            }

            // Store the complete block (header + body)
            // Require CBOR bytes for proper storage
            if (block.getCbor() == null || block.getCbor().isEmpty()) {
                throw new RuntimeException("Block CBOR is required but was null/empty for block: " + hash);
            }

            byte[] blockBytes;
            try {
                blockBytes = HexUtil.decodeHexString(block.getCbor());
            } catch (Exception e) {
                throw new RuntimeException("Invalid block CBOR hex format for block: " + hash + ", CBOR: " + block.getCbor(), e);
            }

            byte[] hashBytes;
            try {
                hashBytes = HexUtil.decodeHexString(hash);
            } catch (Exception e) {
                throw new RuntimeException("Invalid block hash hex format: " + hash, e);
            }

            chainState.storeBlock(
                hashBytes,
                blockNumber,
                slot,
                blockBytes
            );

            // successful store resets stale counter
            consecutiveStaleBlocks.set(0);

            bodiesReceived.incrementAndGet();
            totalBlocksFetched.incrementAndGet();

            // Phase-aware logging: log every block when at tip (STEADY_STATE), otherwise every 100 blocks
            if (syncPhase == SyncPhase.STEADY_STATE) {
                // At tip - log every single block
                log.info("📦 Block: {}, Slot: {} ({})", blockNumber, slot, block.getEra());
            } else {
                // During initial sync - only log every 100 blocks for performance
                if (totalBlocksFetched.get() % 100 == 0) {
                    log.info("📦 Block: {}, Slot: {} ({})", blockNumber, slot, block.getEra());
                }
            }

            if (log.isDebugEnabled() && bodiesReceived.get() % 10 == 0) {
                log.debug("📦 Received {} complete blocks, latest: slot={}, block={}",
                         bodiesReceived.get(), slot, blockNumber);
            }

        } catch (Exception e) {
            log.error("Failed to store complete block: {}",
                     block != null && block.getHeader() != null && block.getHeader().getHeaderBody() != null ?
                     block.getHeader().getHeaderBody().getBlockHash() : "unknown", e);
            throw e; // Re-throw exception for proper error handling
        }
    }

    @Override
    public void onByronBlock(ByronMainBlock byronBlock) {
        if (byronBlock == null || byronBlock.getHeader() == null) {
            log.warn("Received null or incomplete Byron block, skipping storage");
            return;
        }

        try {
            // Handle Byron main block storage
            long slot = byronBlock.getHeader().getConsensusData().getAbsoluteSlot();
            long blockNumber = byronBlock.getHeader().getConsensusData().getDifficulty().longValue();
            String hash = byronBlock.getHeader().getBlockHash();

            // Check for stale blocks that arrived after rollback
            if (isStaleBlock(blockNumber, slot, hash, false)) {
                log.warn("🗑️ DISCARDED STALE BLOCK: Block #{} at slot {} arrived after rollback - skipping storage",
                        blockNumber, slot);
                return;
            }

            // Require CBOR bytes for proper storage
            if (byronBlock.getCbor() == null || byronBlock.getCbor().isEmpty()) {
                throw new RuntimeException("Byron block CBOR is required but was null/empty for block: " + hash);
            }

            byte[] blockBytes;
            try {
                blockBytes = HexUtil.decodeHexString(byronBlock.getCbor());
            } catch (Exception e) {
                throw new RuntimeException("Invalid Byron block CBOR hex format for block: " + hash + ", CBOR: " + byronBlock.getCbor(), e);
            }

            byte[] hashBytes;
            try {
                hashBytes = HexUtil.decodeHexString(hash);
            } catch (Exception e) {
                throw new RuntimeException("Invalid Byron block hash hex format: " + hash, e);
            }

            chainState.storeBlock(
                hashBytes,
                blockNumber,
                slot,
                blockBytes
            );

            bodiesReceived.incrementAndGet();
            totalBlocksFetched.incrementAndGet();

            if (syncPhase == SyncPhase.STEADY_STATE) {
                // At tip - log every single block
                if (totalBlocksFetched.get() % 100 == 0) {
                    log.info("📦--  Block: {}, Slot: {} ({})", blockNumber, slot, "Byron");
                }
            } else {
                // During initial sync - only log every 100 blocks for performance
                if (totalBlocksFetched.get() % 100 == 0) {
                    log.info("📦 Block: {}, Slot: {} ({})", blockNumber, slot, "Byron");
                }
            }

            if (log.isDebugEnabled()) {
                log.debug("📦 Byron block received: slot={}, hash={}", slot, hash);
            }

        } catch (Exception e) {
            log.error("Failed to store Byron block: {}",
                     byronBlock != null && byronBlock.getHeader() != null ?
                     byronBlock.getHeader().getBlockHash() : "unknown", e);
            throw e; // Re-throw exception for proper error handling
        }
    }

    @Override
    public void onByronEbBlock(ByronEbBlock byronEbBlock) {
        if (byronEbBlock == null || byronEbBlock.getHeader() == null) {
            log.warn("Received null or incomplete Byron EB block, skipping storage");
            return;
        }

        try {
            // Handle Byron epoch boundary block storage
            long slot = byronEbBlock.getHeader().getConsensusData().getAbsoluteSlot();
            long blockNumber = byronEbBlock.getHeader().getConsensusData().getDifficulty().longValue();
            String hash = byronEbBlock.getHeader().getBlockHash();

            // Check for stale blocks that arrived after rollback
            if (isStaleBlock(blockNumber, slot, hash, true)) {
                log.warn("🗑️ DISCARDED STALE BLOCK: Byron EB Block #{} at slot {} arrived after rollback - skipping storage",
                        blockNumber, slot);
                onStaleBlockObserved();
                return;
            }

            // Require CBOR bytes for proper storage
            if (byronEbBlock.getCbor() == null || byronEbBlock.getCbor().isEmpty()) {
                throw new RuntimeException("Byron EB block CBOR is required but was null/empty for block: " + hash);
            }

            byte[] blockBytes;
            try {
                blockBytes = HexUtil.decodeHexString(byronEbBlock.getCbor());
            } catch (Exception e) {
                throw new RuntimeException("Invalid Byron EB block CBOR hex format for block: " + hash + ", CBOR: " + byronEbBlock.getCbor(), e);
            }

            byte[] hashBytes;
            try {
                hashBytes = HexUtil.decodeHexString(hash);
            } catch (Exception e) {
                throw new RuntimeException("Invalid Byron EB block hash hex format: " + hash, e);
            }

            chainState.storeBlock(
                hashBytes,
                blockNumber,
                slot,
                blockBytes
            );

            // successful store resets stale counter
            consecutiveStaleBlocks.set(0);

            bodiesReceived.incrementAndGet();
            totalBlocksFetched.incrementAndGet();

            if (log.isDebugEnabled()) {
                log.debug("📦 Byron EB block received: slot={}, hash={}", slot, hash);
            }

        } catch (Exception e) {
            log.error("Failed to store Byron EB block: {}",
                     byronEbBlock != null && byronEbBlock.getHeader() != null ?
                     byronEbBlock.getHeader().getBlockHash() : "unknown", e);
            throw e; // Re-throw exception for proper error handling
        }
    }

    @Override
    public void batchStarted() {
        if (log.isDebugEnabled()) {
            log.debug("📥 Batch fetch started: from={}, to={}, expected size={}",
                     currentBatchFrom != null ? currentBatchFrom.getSlot() : "unknown",
                     currentBatchTo != null ? currentBatchTo.getSlot() : "unknown",
                     currentBatchSize);
        }
    }

    @Override
    public void batchDone() {
        batchInProgress = false;
        batchesCompleted.incrementAndGet();

        if (log.isDebugEnabled()) {
            log.debug("✅ Batch fetch completed: from={}, to={}, received {} blocks",
                     currentBatchFrom != null ? currentBatchFrom.getSlot() : "unknown",
                     currentBatchTo != null ? currentBatchTo.getSlot() : "unknown",
                     currentBatchSize);
        }

        // Reset batch tracking
        currentBatchFrom = null;
        currentBatchTo = null;
        currentBatchSize = 0;
    }

    @Override
    public void noBlockFound(Point from, Point to) {
        log.warn("⚠️ No blocks found in range: from={}, to={}", from, to);
        batchInProgress = false; // Reset state
    }

    @Override
    public void onRollback(Point point) {
        log.info("🔄 Rollback detected to point: {}", point);
        // Store the rollback point to prevent storing stale blocks
        lastRollbackPoint = point;
        // Body fetching will be paused by external rollback handling
        // Reset any in-progress batch
        batchInProgress = false;
        currentBatchFrom = null;
        currentBatchTo = null;
        currentBatchSize = 0;
    }

    @Override
    public void onDisconnect() {
        log.info("💔 Connection lost - pausing body fetch until reconnection");
        batchInProgress = false; // Reset state on disconnect
    }

    @Override
    public void onParsingError(BlockParseRuntimeException e) {
        log.error("🚨 Block parsing error in BodyFetchManager", e);
        // Continue operation despite parsing errors
    }

    // ================================================================
    // Corruption Probing on Repeated Stale Blocks
    // ================================================================

    private void onStaleBlockObserved() {
        int count = consecutiveStaleBlocks.incrementAndGet();

        // Early exit if below threshold or already recovering
        if (count < STALE_RECOVERY_THRESHOLD || recoveryInProgress.get()) return;

        // Single-flight guard
        if (!recoveryInProgress.compareAndSet(false, true)) return;

        log.warn("⚠️ Many consecutive stale blocks observed ({}). Probing for corruption...", count);

        Thread.ofVirtual().start(() -> {
            try {
                // Pause fetching during probe to avoid churn
                paused.set(true);

                if (chainState instanceof com.bloxbean.cardano.yaci.node.runtime.chain.DirectRocksDBChainState rocks) {
                    if (rocks.detectCorruption()) {
                        log.warn("🚨 Corruption detected during runtime probe - attempting recovery");
                        rocks.recoverFromCorruption();
                        log.info("✅ Recovery completed after stale-block probe");
                        // After recovery, reset counters and allow fetching to resume
                        consecutiveStaleBlocks.set(0);
                    } else {
                        log.debug("No corruption detected during stale-block probe");
                    }
                } else {
                    log.debug("ChainState is not RocksDB-backed; skipping runtime corruption probe");
                }
            } catch (Exception e) {
                log.warn("Runtime recovery probe failed: {}", e.toString());
            } finally {
                paused.set(false);
                recoveryInProgress.set(false);
            }
        });
    }

    // ================================================================
    // Status and Metrics
    // ================================================================

    /**
     * Get current status of the BodyFetchManager.
     */
    public BodyFetchStatus getStatus() {
        ChainTip tip = chainState.getTip();
        ChainTip headerTip = chainState.getHeaderTip();

        return new BodyFetchStatus(
            running.get(),
            paused.get(),
            batchInProgress,
            bodiesReceived.get(),
            batchesCompleted.get(),
            calculateGapSize(),  // Calculate gap size on demand instead of using cached value
            tip != null ? tip.getSlot() : null,
            tip != null ? tip.getBlockNumber() : null,
            headerTip != null ? headerTip.getSlot() : null,
            headerTip != null ? headerTip.getBlockNumber() : null,
            totalBlocksFetched.get(),
            System.currentTimeMillis() - startTime
        );
    }

    /**
     * Reset metrics (useful for testing).
     */
    public void resetMetrics() {
        bodiesReceived.set(0);
        batchesCompleted.set(0);
        lastGapSize.set(0);
        totalBlocksFetched.set(0);
        startTime = System.currentTimeMillis();
    }

    /**
     * Check if BodyFetchManager is running.
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Check if BodyFetchManager is paused.
     */
    public boolean isPaused() {
        return paused.get();
    }

    /**
     * Get current gap size (recalculated on demand).
     */
    public long getCurrentGapSize() {
        return calculateGapSize();
    }

    // ================================================================
    // Helper Classes and Methods
    // ================================================================

    /**
     * Check if an incoming block is stale or would create a gap.
     *
     * A block is considered stale/invalid if:
     * 1. The block would create a gap (missing prerequisite blocks)
     * 2. The block is not the immediate next block after current tip
     * 3. A rollback occurred and the block is beyond the rollback point with gaps
     *
     * @param blockNumber The block number of the incoming block
     * @param slot The slot of the incoming block
     * @param hash The hash of the incoming block
     * @return true if the block should be discarded as stale/invalid
     */
    private boolean isStaleBlock(long blockNumber, long slot, String hash, boolean isEbb) {
        try {
            // Get current tip to understand the current state
            ChainTip currentTip = chainState.getTip();

            if (currentTip == null) {
                // If no tip exists:
                // - Allow Byron EBB at genesis (blockNumber may be 0)
                // - Allow main block #1
                if (isEbb) {
                    if (log.isDebugEnabled())
                        log.debug("No tip exists, allowing Byron EBB at slot {} (blockNo={})", slot, blockNumber);
                    return false;
                }
                if (blockNumber == 1) {
                    if (log.isDebugEnabled())
                        log.debug("No tip exists, allowing main block #1 at slot {}", slot);
                    return false;
                }
                if (log.isDebugEnabled())
                    log.debug("No tip exists but incoming block #{} is not allowed as first block - marking as stale", blockNumber);
                return true;
            }

            // Byron EBB handling: allow same-number block at a strictly greater slot
            // when header for that slot exists, since EBB shares difficulty with the prior main block.
            long expectedNextBlockNumber = currentTip.getBlockNumber() + 1;
            if (blockNumber != expectedNextBlockNumber) {
                // Permit special case: same block number but higher slot with a known header (EBB).
                if (isEbb && blockNumber == currentTip.getBlockNumber() && slot > currentTip.getSlot()) {
                    // For EBBs number_by_slot is intentionally not populated; verify by header existence instead.
                    boolean headerPresent;
                    try {
                        headerPresent = chainState.getBlockHeader(HexUtil.decodeHexString(hash)) != null;
                    } catch (Exception e) {
                        headerPresent = false;
                    }
                    if (headerPresent) {
                        if (log.isDebugEnabled())
                            log.debug("Byron EBB allowance: header present for hash {} at slot {} (same blockNumber {}), accepting",
                                    hash, slot, blockNumber);
                        // fall through to prerequisite check
                    } else {
                        if (lastRollbackPoint != null) {
                            log.warn("🚫 Rollback context: rollback was to slot {}, current tip is block {} at slot {}",
                                    lastRollbackPoint.getSlot(), currentTip.getBlockNumber(), currentTip.getSlot());
                        }
                        return true;
                    }
                } else {
                    if (lastRollbackPoint != null) {
                        log.warn("🚫 Rollback context: rollback was to slot {}, current tip is block {} at slot {}",
                                lastRollbackPoint.getSlot(), currentTip.getBlockNumber(), currentTip.getSlot());
                    }
                    return true;
                }
            }

            // Verify the prerequisite block exists (additional safety check)
            if (blockNumber > 1) {
                byte[] previousBlock = chainState.getBlockByNumber(blockNumber - 1);
                if (previousBlock == null) {
                    log.warn("🚫 PREREQUISITE MISSING: Previous block #{} not found for incoming block #{} - marking as stale",
                            blockNumber - 1, blockNumber);
                    return true;
                }
            }

            // If we had a rollback and this block is beyond the rollback point,
            // log additional context but allow it since it passed the sequential check above
            if (lastRollbackPoint != null && slot > lastRollbackPoint.getSlot()) {
                log.debug("✅ Block #{} at slot {} passed sequential check despite being beyond rollback point slot {}",
                         blockNumber, slot, lastRollbackPoint.getSlot());
            }

            // Block is accepted
            return false;

        } catch (Exception e) {
            log.warn("Error checking if block #{} is stale - marking as stale for safety: {}", blockNumber, e.getMessage());
            return true; // If we can't determine safely, discard the block
        }
    }

    /**
     * Represents a block range to fetch.
     */
    private static class BlockRange {
        final Point from;
        final Point to;
        final int size;

        BlockRange(Point from, Point to, int size) {
            this.from = from;
            this.to = to;
            this.size = size;
        }
    }

    /**
     * Status information for BodyFetchManager.
     */
    public static class BodyFetchStatus {
        public final boolean active;
        public final boolean paused;
        public final boolean batchInProgress;
        public final int bodiesReceived;
        public final int batchesCompleted;
        public final long currentGapSize;
        public final Long lastBodySlot;
        public final Long lastBodyBlockNumber;
        public final Long lastHeaderSlot;
        public final Long lastHeaderBlockNumber;
        public final long totalBlocksFetched;
        public final long uptimeMs;

        public BodyFetchStatus(boolean active, boolean paused, boolean batchInProgress,
                              int bodiesReceived, int batchesCompleted, long currentGapSize,
                              Long lastBodySlot, Long lastBodyBlockNumber,
                              Long lastHeaderSlot, Long lastHeaderBlockNumber,
                              long totalBlocksFetched, long uptimeMs) {
            this.active = active;
            this.paused = paused;
            this.batchInProgress = batchInProgress;
            this.bodiesReceived = bodiesReceived;
            this.batchesCompleted = batchesCompleted;
            this.currentGapSize = currentGapSize;
            this.lastBodySlot = lastBodySlot;
            this.lastBodyBlockNumber = lastBodyBlockNumber;
            this.lastHeaderSlot = lastHeaderSlot;
            this.lastHeaderBlockNumber = lastHeaderBlockNumber;
            this.totalBlocksFetched = totalBlocksFetched;
            this.uptimeMs = uptimeMs;
        }
    }

    /**
     * Set the current sync phase. Called by YaciNode to coordinate logging behavior.
     *
     * @param syncPhase The current sync phase
     */
    public void setSyncPhase(SyncPhase syncPhase) {
        SyncPhase oldPhase = this.syncPhase;
        this.syncPhase = syncPhase;
        if (oldPhase != syncPhase) {
            if (log.isDebugEnabled()) {
                log.debug("🔄 BodyFetchManager sync phase changed: {} -> {}", oldPhase, syncPhase);
            }
        }
    }

    /**
     * Get the current sync phase.
     */
    public SyncPhase getSyncPhase() {
        return syncPhase;
    }

    /**
     * Check if we're already near tip and should immediately transition to STEADY_STATE.
     * This enables fast resume when restarting a node that's already synced.
     *
     * IMPORTANT: This now compares against network tip, not just header-body gap.
     * For Byron blocks syncing from early epochs, we don't want to incorrectly
     * detect STEADY_STATE just because headers and bodies are synchronized.
     */
    private void checkForImmediateResume() {
        long headerBodyGap = calculateGapSize();

        // Get network tip from peer client
        ChainTip localTip = chainState.getTip();
        Long networkTipSlot = null;

        try {
            if (peerClient != null && peerClient.isRunning()) {
                var networkTipOpt = peerClient.getLatestTip();
                if (networkTipOpt.isPresent()) {
                    networkTipSlot = networkTipOpt.get().getPoint().getSlot();
                    log.debug("📡 Retrieved network tip: slot={}", networkTipSlot);
                } else {
                    log.debug("📡 Network tip not available yet from peer client");
                }
            } else {
                log.debug("📡 PeerClient not running, cannot get network tip");
            }
        } catch (Exception e) {
            log.debug("Could not get network tip for sync phase detection: {}", e.getMessage());
        }

        // Calculate distance from network tip
        long distanceFromNetworkTip = Long.MAX_VALUE;
        if (localTip != null && networkTipSlot != null) {
            distanceFromNetworkTip = networkTipSlot - localTip.getSlot();
        }

        // Only transition to STEADY_STATE if we're actually near the network tip
        // Use a larger threshold (1000 slots) for network tip proximity since Byron blocks
        // are much older than current tip
        long networkTipThreshold = 1000;
        boolean nearNetworkTip = (networkTipSlot != null) && (distanceFromNetworkTip <= networkTipThreshold);

        // IMPORTANT: Default to INITIAL_SYNC if we can't determine network tip
        // This prevents incorrectly detecting STEADY_STATE for Byron blocks
        if (nearNetworkTip && headerBodyGap <= tipProximityThreshold) {
            // Transition immediately to STEADY_STATE for real-time logging
            syncPhase = SyncPhase.STEADY_STATE;

            ChainTip tip = chainState.getTip();
            ChainTip headerTip = chainState.getHeaderTip();

            log.info("⚡ IMMEDIATE RESUME: Already near network tip (distance={} slots <= threshold={})",
                     distanceFromNetworkTip, networkTipThreshold);
            log.info("⚡ Current state: body tip={}, header tip={}, network tip={}",
                     tip != null ? "slot=" + tip.getSlot() : "null",
                     headerTip != null ? "slot=" + headerTip.getSlot() : "null",
                     networkTipSlot != null ? "slot=" + networkTipSlot : "unknown");
            log.info("⚡ Transitioned directly to STEADY_STATE - will log every block");

            // Don't pause since we're already at tip
            paused.set(false);
        } else {
            log.info("📊 Starting INITIAL_SYNC: header-body gap={} slots, network distance={} slots (threshold={})",
                     headerBodyGap,
                     distanceFromNetworkTip != Long.MAX_VALUE ? distanceFromNetworkTip : "unknown",
                     networkTipThreshold);
            log.info("📊 Will log every 100 blocks during initial sync, every block when near tip");
        }
    }

    // Helper methods removed - using HexUtil instead
}
