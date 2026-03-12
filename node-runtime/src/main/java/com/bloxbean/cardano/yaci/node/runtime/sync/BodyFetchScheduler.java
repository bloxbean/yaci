package com.bloxbean.cardano.yaci.node.runtime.sync;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.helper.PeerClient;
import com.bloxbean.cardano.yaci.node.runtime.peer.PeerConnection;
import com.bloxbean.cardano.yaci.node.runtime.peer.PeerPool;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;

/**
 * Schedules block body fetches across multiple peers with failover.
 * <p>
 * Initial simple version: fetches from the best peer (same as current single-peer behavior).
 * On failure, fails over to the next best connected peer.
 * <p>
 * Future enhancement: parallel fetch from multiple peers by splitting ranges.
 */
@Slf4j
public class BodyFetchScheduler {

    private final PeerPool peerPool;

    public BodyFetchScheduler(PeerPool peerPool) {
        this.peerPool = peerPool;
    }

    /**
     * Fetch a range of blocks, starting with the best peer and failing over to others.
     *
     * @param from start point (inclusive)
     * @param to   end point (inclusive)
     * @return true if the fetch was initiated on some peer, false if no peer available
     */
    public boolean fetch(Point from, Point to) {
        // Try best peer first
        Optional<PeerConnection> best = peerPool.getBestPeer();
        if (best.isPresent()) {
            PeerConnection conn = best.get();
            Optional<PeerClient> client = conn.getPeerClient();
            if (client.isPresent() && conn.isConnected()) {
                try {
                    client.get().fetch(from, to);
                    log.debug("Fetch initiated on best peer {}: [{} -> {}]",
                            conn.getPeerId(), from, to);
                    return true;
                } catch (Exception e) {
                    log.warn("Fetch failed on best peer {}: {}", conn.getPeerId(), e.getMessage());
                    conn.recordFailure();
                }
            }
        }

        // Failover to other connected peers
        List<PeerConnection> hotPeers = peerPool.getHotPeers();
        for (PeerConnection conn : hotPeers) {
            if (best.isPresent() && conn.getPeerId().equals(best.get().getPeerId())) {
                continue; // already tried
            }
            Optional<PeerClient> client = conn.getPeerClient();
            if (client.isPresent()) {
                try {
                    client.get().fetch(from, to);
                    log.info("Fetch failover to peer {}: [{} -> {}]",
                            conn.getPeerId(), from, to);
                    return true;
                } catch (Exception e) {
                    log.warn("Fetch also failed on peer {}: {}", conn.getPeerId(), e.getMessage());
                    conn.recordFailure();
                }
            }
        }

        log.error("No connected peers available for fetch [{} -> {}]", from, to);
        return false;
    }

    /**
     * Pause block fetching on all connected peers.
     */
    public void pauseAll() {
        for (PeerConnection conn : peerPool.getHotPeers()) {
            conn.getPeerClient().ifPresent(PeerClient::pauseBlockFetch);
        }
    }

    /**
     * Resume block fetching on all connected peers.
     */
    public void resumeAll() {
        for (PeerConnection conn : peerPool.getHotPeers()) {
            conn.getPeerClient().ifPresent(PeerClient::resumeBlockFetch);
        }
    }
}
