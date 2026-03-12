package com.bloxbean.cardano.yaci.node.runtime.peer;

import com.bloxbean.cardano.yaci.node.api.chain.ChainCandidate;
import com.bloxbean.cardano.yaci.node.api.chain.ChainSelectionStrategy;
import com.bloxbean.cardano.yaci.node.api.config.PeerType;
import com.bloxbean.cardano.yaci.node.api.config.UpstreamConfig;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Manages multiple upstream peer connections.
 * <p>
 * Tracks per-peer tip, health, and latency. Provides methods to select the best peer
 * based on a pluggable {@link ChainSelectionStrategy}.
 * <p>
 * Thread-safe: all peer map operations use ConcurrentHashMap.
 */
@Slf4j
public class PeerPool {

    private final ConcurrentHashMap<String, PeerConnection> peers = new ConcurrentHashMap<>();
    private final ChainSelectionStrategy chainSelectionStrategy;

    // Track which peer we are currently following
    private volatile String bestPeerId;

    public PeerPool(ChainSelectionStrategy chainSelectionStrategy) {
        this.chainSelectionStrategy = chainSelectionStrategy;
    }

    /**
     * Add a new peer to the pool. Does not connect — call
     * {@link PeerConnection#createPeerClient(long, com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point)}
     * on the returned connection to establish the connection.
     */
    public PeerConnection addPeer(UpstreamConfig config) {
        String peerId = config.peerId();
        if (peers.containsKey(peerId)) {
            log.warn("Peer already in pool: {}", peerId);
            return peers.get(peerId);
        }

        PeerConnection conn = new PeerConnection(config);
        peers.put(peerId, conn);
        log.info("Added peer to pool: {} (type={})", peerId, config.getType());
        return conn;
    }

    /**
     * Remove a peer from the pool and stop its connection.
     */
    public void removePeer(String peerId) {
        PeerConnection conn = peers.remove(peerId);
        if (conn != null) {
            conn.stop();
            log.info("Removed peer from pool: {}", peerId);
            if (peerId.equals(bestPeerId)) {
                bestPeerId = null;
                reEvaluateBestPeer();
            }
        }
    }

    /**
     * Get a peer connection by ID.
     */
    public Optional<PeerConnection> getPeer(String peerId) {
        return Optional.ofNullable(peers.get(peerId));
    }

    /**
     * Get all connected, healthy peers.
     */
    public List<PeerConnection> getHotPeers() {
        return peers.values().stream()
                .filter(PeerConnection::isConnected)
                .collect(Collectors.toList());
    }

    /**
     * Get all peers (connected or not).
     */
    public Collection<PeerConnection> getAllPeers() {
        return Collections.unmodifiableCollection(peers.values());
    }

    /**
     * Get peers filtered by type.
     */
    public List<PeerConnection> getPeersByType(PeerType type) {
        return peers.values().stream()
                .filter(p -> p.getPeerType() == type)
                .collect(Collectors.toList());
    }

    /**
     * Get the peer we are currently following (best chain).
     */
    public Optional<PeerConnection> getBestPeer() {
        if (bestPeerId != null) {
            PeerConnection best = peers.get(bestPeerId);
            if (best != null && best.isConnected()) {
                return Optional.of(best);
            }
        }
        // Re-evaluate if cached best is stale
        reEvaluateBestPeer();
        return bestPeerId != null ? Optional.ofNullable(peers.get(bestPeerId)) : Optional.empty();
    }

    /**
     * Get the current best peer ID.
     */
    public String getBestPeerId() {
        return bestPeerId;
    }

    /**
     * Re-evaluate which peer has the best chain among all connected peers.
     * A peer is eligible if it has reported at least one tip (tipBlockNumber >= 0).
     * Returns the new best peer ID, or null if no eligible peers exist.
     */
    public String reEvaluateBestPeer() {
        List<PeerConnection> hotPeers = peers.values().stream()
                .filter(p -> p.getTipBlockNumber() >= 0)
                .collect(Collectors.toList());
        if (hotPeers.isEmpty()) {
            bestPeerId = null;
            return null;
        }

        PeerConnection best = hotPeers.get(0);
        for (int i = 1; i < hotPeers.size(); i++) {
            PeerConnection candidate = hotPeers.get(i);
            ChainCandidate candidateChain = toChainCandidate(candidate);
            ChainCandidate bestChain = toChainCandidate(best);

            if (chainSelectionStrategy.compare(candidateChain, bestChain) > 0) {
                best = candidate;
            }
        }

        String previousBest = bestPeerId;
        bestPeerId = best.getPeerId();

        if (!bestPeerId.equals(previousBest)) {
            log.info("Best peer changed: {} -> {} (blockNo={}, slot={})",
                    previousBest, bestPeerId, best.getTipBlockNumber(), best.getTipSlot());
        }

        return bestPeerId;
    }

    /**
     * Build a {@link ChainCandidate} from a peer's current tip.
     */
    public ChainCandidate toChainCandidate(PeerConnection peer) {
        return new ChainCandidate(
                peer.getPeerId(),
                peer.getTipBlockNumber(),
                peer.getTipSlot(),
                peer.getTipHash(),
                null,  // intersection point — computed by HeaderFanIn when comparing against our chain
                0      // fork depth — computed by HeaderFanIn
        );
    }

    /**
     * Get the chain selection strategy used by this pool.
     */
    public ChainSelectionStrategy getChainSelectionStrategy() {
        return chainSelectionStrategy;
    }

    /**
     * Number of peers in the pool (connected or not).
     */
    public int size() {
        return peers.size();
    }

    /**
     * Number of connected peers.
     */
    public int connectedCount() {
        return (int) peers.values().stream().filter(PeerConnection::isConnected).count();
    }

    /**
     * Stop all peer connections and clear the pool.
     */
    public void shutdown() {
        log.info("Shutting down PeerPool ({} peers)", peers.size());
        for (PeerConnection conn : peers.values()) {
            conn.stop();
        }
        peers.clear();
        bestPeerId = null;
    }
}
