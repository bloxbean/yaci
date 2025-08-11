package com.bloxbean.cardano.yaci.node.runtime;

import com.bloxbean.cardano.yaci.core.common.GenesisConfig;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.byron.ByronBlockHead;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbHead;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.n2n.ChainSyncAgentListener;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.helper.PeerClient;
import lombok.extern.slf4j.Slf4j;

/**
 * HeaderSyncManager handles header-only synchronization using ChainSyncAgent.
 *
 * This component:
 * - Implements ChainSyncAgentListener to receive header events
 * - Stores headers immediately via chainState.storeBlockHeader()
 * - Updates header_tip in ChainState after each header storage
 * - Handles Byron and Shelley+ era headers with proper CBOR storage
 * - Relies on ChainSyncAgent's automatic reconnection (no manual reconnection logic)
 *
 * The HeaderSyncManager works in parallel with BodyFetchManager to achieve
 * true pipeline synchronization performance.
 */
@Slf4j
public class HeaderSyncManager implements ChainSyncAgentListener {

    private final ChainState chainState;
    private final PeerClient peerClient;

    // Metrics tracking
    private volatile long headersReceived = 0;
    private volatile long shelleyHeadersReceived = 0;
    private volatile long byronHeadersReceived = 0;
    private volatile long byronEbHeadersReceived = 0;

    // Progress logging
    private static final int PROGRESS_LOG_INTERVAL = 1000;

    public HeaderSyncManager(PeerClient peerClient, ChainState chainState) {
        this.peerClient = peerClient;
        this.chainState = chainState;

        log.info("HeaderSyncManager initialized - ready for header-only synchronization");
    }

    // =================================================================
    // ChainSyncAgentListener Implementation - Shelley+ Era Headers
    // =================================================================

    @Override
    public void rollforward(Tip tip, BlockHeader blockHeader, byte[] originalHeaderBytes) {
        try {
            // Validate input parameters
            if (blockHeader == null || originalHeaderBytes == null || originalHeaderBytes.length == 0) {
                log.warn("Invalid header data received: blockHeader={}, originalHeaderBytes={}",
                        blockHeader != null ? "present" : "null",
                        originalHeaderBytes != null ? originalHeaderBytes.length : "null");
                return;
            }

            long slot = blockHeader.getHeaderBody().getSlot();
            long blockNumber = blockHeader.getHeaderBody().getBlockNumber();
            String blockHash = blockHeader.getHeaderBody().getBlockHash();

            // Store header immediately when received from ChainSync
            chainState.storeBlockHeader(
                HexUtil.decodeHexString(blockHash),
                blockNumber,
                slot,
                originalHeaderBytes
            );

            // Update metrics
            headersReceived++;
            shelleyHeadersReceived++;

            // Log progress periodically
            if (headersReceived % PROGRESS_LOG_INTERVAL == 0) {
                log.info("📄 Headers: {} received (Shelley+ Block #{} at slot {})",
                        headersReceived, blockNumber, slot);
            }

            if (log.isDebugEnabled()) {
                log.debug("Stored Shelley+ header: slot={}, blockNumber={}, hash={}",
                        slot, blockNumber, blockHash);
            }

        } catch (Exception e) {
            log.error("Failed to store Shelley+ header: slot={}, blockNumber={}",
                    blockHeader.getHeaderBody().getSlot(),
                    blockHeader.getHeaderBody().getBlockNumber(), e);
            throw new RuntimeException("Failed to store Shelley+ header", e);
        }
    }

    // =================================================================
    // ChainSyncAgentListener Implementation - Byron Era Headers
    // =================================================================

    @Override
    public void rollforwardByronEra(Tip tip, ByronBlockHead byronBlockHead, byte[] originalHeaderBytes) {
        try {
            // Validate input parameters
            if (byronBlockHead == null || originalHeaderBytes == null || originalHeaderBytes.length == 0) {
                log.warn("Invalid Byron header data received: byronBlockHead={}, originalHeaderBytes={}",
                        byronBlockHead != null ? "present" : "null",
                        originalHeaderBytes != null ? originalHeaderBytes.length : "null");
                return;
            }

            long absoluteSlot = GenesisConfig.getInstance().absoluteSlot(Era.Byron,
                    byronBlockHead.getConsensusData().getSlotId().getEpoch(),
                    byronBlockHead.getConsensusData().getSlotId().getSlot());

            long blockNumber = byronBlockHead.getConsensusData().getDifficulty().longValue();
            String blockHash = byronBlockHead.getBlockHash();

            // Store Byron header immediately when received from ChainSync
            chainState.storeBlockHeader(
                HexUtil.decodeHexString(blockHash),
                blockNumber,
                absoluteSlot,
                originalHeaderBytes
            );

            // Update metrics
            headersReceived++;
            byronHeadersReceived++;

            // Log progress periodically
            if (headersReceived % PROGRESS_LOG_INTERVAL == 0) {
                log.info("📄 Headers: {} received (Byron Block #{} at slot {})",
                        headersReceived, blockNumber, absoluteSlot);
            }

            if (log.isDebugEnabled()) {
                log.debug("Stored Byron header: slot={}, blockNumber={}, hash={}",
                        absoluteSlot, blockNumber, blockHash);
            }

        } catch (Exception e) {
            log.error("Failed to store Byron header: slot={}, blockNumber={}",
                    byronBlockHead.getConsensusData().getAbsoluteSlot(),
                    byronBlockHead.getConsensusData().getDifficulty().longValue(), e);
            throw new RuntimeException("Failed to store Byron header", e);
        }
    }

    @Override
    public void rollforwardByronEra(Tip tip, ByronEbHead byronEbHead, byte[] originalHeaderBytes) {
        try {
            // Validate input parameters
            if (byronEbHead == null || originalHeaderBytes == null || originalHeaderBytes.length == 0) {
                log.warn("Invalid Byron EB header data received: byronEbHead={}, originalHeaderBytes={}",
                        byronEbHead != null ? "present" : "null",
                        originalHeaderBytes != null ? originalHeaderBytes.length : "null");
                return;
            }

            long absoluteSlot = GenesisConfig.getInstance().absoluteSlot(Era.Byron,
                    byronEbHead.getConsensusData().getEpoch(),
                    0);

            long blockNumber = byronEbHead.getConsensusData().getDifficulty().longValue();
            String blockHash = byronEbHead.getBlockHash();

            // Store Byron EB header immediately when received from ChainSync
            chainState.storeBlockHeader(
                HexUtil.decodeHexString(blockHash),
                blockNumber,
                absoluteSlot,
                originalHeaderBytes
            );

            // Update metrics
            headersReceived++;
            byronEbHeadersReceived++;

            // Log progress periodically
            if (headersReceived % PROGRESS_LOG_INTERVAL == 0) {
                log.info("📄 Headers: {} received (Byron EB Block #{} at slot {})",
                        headersReceived, blockNumber, absoluteSlot);
            }

            if (log.isDebugEnabled()) {
                log.debug("Stored Byron EB header: slot={}, blockNumber={}, hash={}",
                        absoluteSlot, blockNumber, blockHash);
            }

        } catch (Exception e) {
            log.error("Failed to store Byron EB header: slot={}, blockNumber={}",
                    byronEbHead.getConsensusData().getAbsoluteSlot(),
                    byronEbHead.getConsensusData().getDifficulty().longValue(), e);
            throw new RuntimeException("Failed to store Byron EB header", e);
        }
    }

    // =================================================================
    // ChainSyncAgentListener Implementation - Control Flow Methods
    // =================================================================

    @Override
    public void intersactFound(Tip tip, Point point) {
        log.info("📄 Header intersection found at: {} (tip: {})", point, tip);
        // ChainSyncAgent automatically resumes from this point on reconnection
        // No manual state management needed
    }

    @Override
    public void intersactNotFound(Tip tip) {
        log.warn("📄 Header intersection not found. Tip: {}", tip);
        // ChainSyncAgent will handle this scenario
        // This typically results in a rollback to find a common point
    }

    @Override
    public void rollbackward(Tip tip, Point toPoint) {
        log.info("📄 Header rollback requested to: {} (tip: {})", toPoint, tip);
        // The actual rollback will be handled by YaciNode.onRollback()
        // which coordinates both header and body rollback
        // ChainSyncAgent automatically adjusts its currentPoint
    }

    @Override
    public void onDisconnect() {
        log.info("📄 Header sync disconnected - will auto-reconnect from last confirmed point");
        // No action needed here - ChainSyncAgent handles reconnection automatically
        // using its internal currentPoint tracking for robust resumption
        log.debug("📄 ChainSyncAgent will automatically resume headers from last confirmed point");
    }

    // =================================================================
    // Metrics and Status Methods
    // =================================================================

    /**
     * Get total headers received across all eras
     */
    public long getHeadersReceived() {
        return headersReceived;
    }

    /**
     * Get headers received by era for detailed metrics
     */
    public HeaderMetrics getHeaderMetrics() {
        return HeaderMetrics.builder()
                .totalHeaders(headersReceived)
                .shelleyHeaders(shelleyHeadersReceived)
                .byronHeaders(byronHeadersReceived)
                .byronEbHeaders(byronEbHeadersReceived)
                .build();
    }

    /**
     * Get current header sync status
     */
    public HeaderSyncStatus getStatus() {
        var headerTip = chainState.getHeaderTip();
        return HeaderSyncStatus.builder()
                .active(peerClient != null && peerClient.isRunning())
                .headersReceived(headersReceived)
                .currentHeaderTip(headerTip)
                .lastHeaderSlot(headerTip != null ? headerTip.getSlot() : null)
                .lastHeaderBlockNumber(headerTip != null ? headerTip.getBlockNumber() : null)
                .build();
    }

    /**
     * Reset metrics (useful for testing)
     */
    public void resetMetrics() {
        headersReceived = 0;
        shelleyHeadersReceived = 0;
        byronHeadersReceived = 0;
        byronEbHeadersReceived = 0;
        log.debug("📄 Header sync metrics reset");
    }

    // =================================================================
    // Inner Classes for Metrics and Status
    // =================================================================

    public static class HeaderMetrics {
        public final long totalHeaders;
        public final long shelleyHeaders;
        public final long byronHeaders;
        public final long byronEbHeaders;

        private HeaderMetrics(long totalHeaders, long shelleyHeaders, long byronHeaders, long byronEbHeaders) {
            this.totalHeaders = totalHeaders;
            this.shelleyHeaders = shelleyHeaders;
            this.byronHeaders = byronHeaders;
            this.byronEbHeaders = byronEbHeaders;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private long totalHeaders;
            private long shelleyHeaders;
            private long byronHeaders;
            private long byronEbHeaders;

            public Builder totalHeaders(long totalHeaders) { this.totalHeaders = totalHeaders; return this; }
            public Builder shelleyHeaders(long shelleyHeaders) { this.shelleyHeaders = shelleyHeaders; return this; }
            public Builder byronHeaders(long byronHeaders) { this.byronHeaders = byronHeaders; return this; }
            public Builder byronEbHeaders(long byronEbHeaders) { this.byronEbHeaders = byronEbHeaders; return this; }

            public HeaderMetrics build() {
                return new HeaderMetrics(totalHeaders, shelleyHeaders, byronHeaders, byronEbHeaders);
            }
        }

        @Override
        public String toString() {
            return String.format("HeaderMetrics{total=%d, shelley=%d, byron=%d, byronEb=%d}",
                                totalHeaders, shelleyHeaders, byronHeaders, byronEbHeaders);
        }
    }

    public static class HeaderSyncStatus {
        public final boolean active;
        public final long headersReceived;
        public final Object currentHeaderTip; // ChainTip object
        public final Long lastHeaderSlot;
        public final Long lastHeaderBlockNumber;

        private HeaderSyncStatus(boolean active, long headersReceived, Object currentHeaderTip,
                               Long lastHeaderSlot, Long lastHeaderBlockNumber) {
            this.active = active;
            this.headersReceived = headersReceived;
            this.currentHeaderTip = currentHeaderTip;
            this.lastHeaderSlot = lastHeaderSlot;
            this.lastHeaderBlockNumber = lastHeaderBlockNumber;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private boolean active;
            private long headersReceived;
            private Object currentHeaderTip;
            private Long lastHeaderSlot;
            private Long lastHeaderBlockNumber;

            public Builder active(boolean active) { this.active = active; return this; }
            public Builder headersReceived(long headersReceived) { this.headersReceived = headersReceived; return this; }
            public Builder currentHeaderTip(Object currentHeaderTip) { this.currentHeaderTip = currentHeaderTip; return this; }
            public Builder lastHeaderSlot(Long lastHeaderSlot) { this.lastHeaderSlot = lastHeaderSlot; return this; }
            public Builder lastHeaderBlockNumber(Long lastHeaderBlockNumber) { this.lastHeaderBlockNumber = lastHeaderBlockNumber; return this; }

            public HeaderSyncStatus build() {
                return new HeaderSyncStatus(active, headersReceived, currentHeaderTip, lastHeaderSlot, lastHeaderBlockNumber);
            }
        }

        @Override
        public String toString() {
            return String.format("HeaderSyncStatus{active=%b, headers=%d, slot=%s, blockNum=%s}",
                                active, headersReceived, lastHeaderSlot, lastHeaderBlockNumber);
        }
    }
}
