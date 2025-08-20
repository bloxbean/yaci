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
     * Main monitoring loop - runs continuously checking for gaps.
     */
    @Override
    public void run() {
        log.info("📊 BodyFetchManager monitoring thread started");

        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                if (!paused.get()) {
                    checkAndFetchBodies();
                }

                Thread.sleep(monitoringIntervalMs);

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
        log.debug("🔍 Gap check: headerTip={}, tip={}, gapSize={}, threshold={}",
                 headerTip != null ? "slot=" + headerTip.getSlot() : "null",
                 tip != null ? "slot=" + tip.getSlot() : "null",
                 gapSize, gapThreshold);

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
     * Determine if bodies should be fetched based on gap size.
     */
    private boolean shouldFetchBodies(long gapSize) {
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
                log.info("📦 Block: {}, Slot: {} ({})", blockNumber, slot, "Byron");
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
     */
    private void checkForImmediateResume() {
        long gapSize = calculateGapSize();

        // If gap is small (within tipProximityThreshold), we're already near tip
        if (gapSize <= tipProximityThreshold) {
            // Transition immediately to STEADY_STATE for real-time logging
            syncPhase = SyncPhase.STEADY_STATE;

            ChainTip tip = chainState.getTip();
            ChainTip headerTip = chainState.getHeaderTip();

            log.info("⚡ IMMEDIATE RESUME: Already near tip (gap={} slots <= threshold={})",
                     gapSize, tipProximityThreshold);
            log.info("⚡ Current state: body tip={}, header tip={}",
                     tip != null ? "slot=" + tip.getSlot() : "null",
                     headerTip != null ? "slot=" + headerTip.getSlot() : "null");
            log.info("⚡ Transitioned directly to STEADY_STATE - will log every block");

            // Don't pause since we're already at tip
            paused.set(false);
        } else {
            log.info("📊 Starting with gap of {} slots (threshold={}), beginning sync",
                     gapSize, tipProximityThreshold);
        }
    }

    // Helper methods removed - using HexUtil instead
}
