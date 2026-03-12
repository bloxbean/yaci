package com.bloxbean.cardano.yaci.node.runtime.sync;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.api.EventMetadata;
import com.bloxbean.cardano.yaci.events.api.PublishOptions;
import com.bloxbean.cardano.yaci.node.api.chain.ChainCandidate;
import com.bloxbean.cardano.yaci.node.api.chain.ChainSelectionStrategy;
import com.bloxbean.cardano.yaci.node.api.events.ChainCandidateEvent;
import com.bloxbean.cardano.yaci.node.api.events.ChainSwitchEvent;
import com.bloxbean.cardano.yaci.node.runtime.peer.PeerConnection;
import com.bloxbean.cardano.yaci.node.runtime.peer.PeerPool;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Receives headers from multiple peers and selects the best chain.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Deduplicate headers by block hash (same block from multiple peers → process once)</li>
 *   <li>Build {@link ChainCandidate} from each peer's tip</li>
 *   <li>Publish {@link ChainCandidateEvent} — plugins can reject bad candidates</li>
 *   <li>Apply {@link ChainSelectionStrategy} to pick the best chain</li>
 *   <li>Track which peer we are currently following</li>
 *   <li>On peer disconnect: re-evaluate best chain from remaining peers</li>
 * </ul>
 */
@Slf4j
public class HeaderFanIn {

    private final PeerPool peerPool;
    private final ChainState chainState;
    private final EventBus eventBus;
    private final ChainSelectionStrategy chainSelectionStrategy;

    // Dedup: block hashes we have already seen
    private final Set<String> seenBlockHashes = ConcurrentHashMap.newKeySet();

    // Current best chain tracking
    private volatile String bestPeerId;
    private volatile long bestBlockNumber = -1;
    private volatile long bestSlot = -1;
    private volatile String bestHash;

    // Limit dedup set size to prevent memory leak during long syncs
    private static final int MAX_SEEN_HASHES = 100_000;

    public HeaderFanIn(PeerPool peerPool, ChainState chainState, EventBus eventBus,
                       ChainSelectionStrategy chainSelectionStrategy) {
        this.peerPool = peerPool;
        this.chainState = chainState;
        this.eventBus = eventBus;
        this.chainSelectionStrategy = chainSelectionStrategy;
    }

    /**
     * Called when a peer reports a new tip via ChainSync rollforward.
     * This is the main entry point for header fan-in.
     *
     * @param peerId    the peer that reported the header
     * @param tip       the peer's reported tip
     * @param blockHash hex-encoded block hash of the received header
     * @param blockNumber block number of the received header
     * @param slot      slot number of the received header
     * @return true if this header should be processed (not a duplicate), false if deduplicated away
     */
    public boolean onHeaderReceived(String peerId, Tip tip, String blockHash,
                                    long blockNumber, long slot) {
        // Step 1: Dedup by block hash
        if (!seenBlockHashes.add(blockHash)) {
            log.trace("Duplicate header from {}: block {} hash {}", peerId, blockNumber, blockHash);
            return false;
        }

        // Evict old entries to prevent unbounded growth
        if (seenBlockHashes.size() > MAX_SEEN_HASHES) {
            // Simple eviction: clear and start fresh. In practice, old hashes
            // are no longer relevant once we've moved past them.
            seenBlockHashes.clear();
            seenBlockHashes.add(blockHash);
        }

        // Step 2: Update peer's tip in the pool
        peerPool.getPeer(peerId).ifPresent(conn -> {
            conn.updateTip(tip);
            conn.updateTip(blockNumber, slot, blockHash);
        });

        // Step 3: Evaluate as chain candidate if it's better than current best
        if (blockNumber > bestBlockNumber ||
                (blockNumber == bestBlockNumber && !blockHash.equals(bestHash))) {

            ChainCandidate candidate = new ChainCandidate(
                    peerId, blockNumber, slot, blockHash,
                    null, // intersection point — not computed yet for performance
                    computeForkDepth(blockNumber)
            );

            // Check fork depth against k
            if (candidate.forkDepth() > chainSelectionStrategy.maxRollbackDepth()) {
                log.warn("Rejecting candidate from {}: fork depth {} exceeds k={}",
                        peerId, candidate.forkDepth(), chainSelectionStrategy.maxRollbackDepth());
                return true; // header is not duplicate, but candidate is rejected
            }

            // Step 4: Publish ChainCandidateEvent — plugins can veto
            ChainCandidateEvent event = new ChainCandidateEvent(
                    candidate, bestBlockNumber, bestSlot,
                    bestHash != null ? bestHash : "");

            if (eventBus != null) {
                eventBus.publish(event, EventMetadata.builder().build(), PublishOptions.builder().build());
                if (event.isRejected()) {
                    log.info("Chain candidate from {} rejected by plugin: {}",
                            peerId, event.rejections());
                    return true;
                }
            }

            // Step 5: Compare against current best using strategy
            if (bestHash == null) {
                // First candidate — accept it
                switchToBestPeer(peerId, blockNumber, slot, blockHash);
            } else {
                ChainCandidate currentBest = new ChainCandidate(
                        bestPeerId, bestBlockNumber, bestSlot, bestHash, null, 0);

                if (chainSelectionStrategy.compare(candidate, currentBest) > 0) {
                    switchToBestPeer(peerId, blockNumber, slot, blockHash);
                }
            }
        } else {
            // This header extends the current best chain (common case during normal sync)
            if (peerId.equals(bestPeerId) && blockNumber > bestBlockNumber) {
                bestBlockNumber = blockNumber;
                bestSlot = slot;
                bestHash = blockHash;
            }
        }

        return true; // not a duplicate
    }

    /**
     * Called when a peer disconnects. Re-evaluate best chain from remaining peers.
     */
    public void onPeerDisconnected(String peerId) {
        if (peerId.equals(bestPeerId)) {
            log.info("Best peer {} disconnected, re-evaluating...", peerId);
            String newBest = peerPool.reEvaluateBestPeer();
            if (newBest != null) {
                PeerConnection conn = peerPool.getPeer(newBest).orElse(null);
                if (conn != null) {
                    switchToBestPeer(newBest, conn.getTipBlockNumber(),
                            conn.getTipSlot(), conn.getTipHash());
                }
            } else {
                bestPeerId = null;
                log.warn("No connected peers remaining after {} disconnected", peerId);
            }
        }
    }

    private void switchToBestPeer(String newPeerId, long blockNumber, long slot, String hash) {
        String previousPeerId = bestPeerId;
        long previousBlockNumber = bestBlockNumber;
        long previousSlot = bestSlot;
        long rollbackDepth = computeForkDepth(blockNumber);

        bestPeerId = newPeerId;
        bestBlockNumber = blockNumber;
        bestSlot = slot;
        bestHash = hash;

        if (previousPeerId != null && !previousPeerId.equals(newPeerId)) {
            log.info("Chain switch: {} (block {}) -> {} (block {}), rollback depth: {}",
                    previousPeerId, previousBlockNumber, newPeerId, blockNumber, rollbackDepth);

            if (eventBus != null) {
                eventBus.publish(new ChainSwitchEvent(
                        previousPeerId, previousBlockNumber, previousSlot,
                        newPeerId, blockNumber, slot, rollbackDepth),
                        EventMetadata.builder().build(), PublishOptions.builder().build());
            }
        }

        // Update PeerPool's best peer tracking
        peerPool.reEvaluateBestPeer();
    }

    /**
     * Compute how many blocks back from our current tip this candidate forks.
     */
    private long computeForkDepth(long candidateBlockNumber) {
        ChainTip currentTip = chainState.getTip();
        if (currentTip == null) return 0;

        long ourBlockNumber = currentTip.getBlockNumber();
        // If candidate is ahead, fork depth is 0 (extension)
        // If candidate is behind, fork depth is the difference
        if (candidateBlockNumber >= ourBlockNumber) return 0;
        return ourBlockNumber - candidateBlockNumber;
    }

    // Accessors for current best chain state

    public String getBestPeerId() { return bestPeerId; }
    public long getBestBlockNumber() { return bestBlockNumber; }
    public long getBestSlot() { return bestSlot; }
    public String getBestHash() { return bestHash; }
}
