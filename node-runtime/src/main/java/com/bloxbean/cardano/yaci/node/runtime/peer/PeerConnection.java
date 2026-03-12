package com.bloxbean.cardano.yaci.node.runtime.peer;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.helper.PeerClient;
import com.bloxbean.cardano.yaci.node.api.config.PeerType;
import com.bloxbean.cardano.yaci.node.api.config.UpstreamConfig;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Wraps a single {@link PeerClient} with connection metadata and health tracking.
 * Each upstream peer has one PeerConnection managed by {@link PeerPool}.
 */
@Slf4j
public class PeerConnection {

    @Getter
    private final String peerId;
    @Getter
    private final UpstreamConfig config;
    @Getter
    private final PeerType peerType;

    private volatile PeerClient peerClient;

    // Tip tracking
    private final AtomicReference<Tip> currentTip = new AtomicReference<>();
    private final AtomicLong tipBlockNumber = new AtomicLong(-1);
    private final AtomicLong tipSlot = new AtomicLong(-1);
    private final AtomicReference<String> tipHash = new AtomicReference<>();

    // Health metrics
    @Getter
    private volatile Instant connectedAt;
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicLong latencyMs = new AtomicLong(-1);
    private volatile boolean connected = false;

    public PeerConnection(UpstreamConfig config) {
        this.config = config;
        this.peerId = config.peerId();
        this.peerType = config.getType();
    }

    /**
     * Create and return a new PeerClient for this connection.
     * The caller is responsible for connecting and starting sync on the returned client.
     * App protocol configuration can be done via {@code getPeerClient().get().getAppProtocolManager()}.
     */
    public PeerClient createPeerClient(long protocolMagic, Point wellKnownPoint) {
        if (peerClient != null && peerClient.isRunning()) {
            throw new IllegalStateException("PeerClient already running for " + peerId);
        }
        peerClient = new PeerClient(config.getHost(), config.getPort(), protocolMagic, wellKnownPoint);
        return peerClient;
    }

    /**
     * Get the underlying PeerClient, if created.
     */
    public Optional<PeerClient> getPeerClient() {
        return Optional.ofNullable(peerClient);
    }

    /**
     * Update the peer's current tip from a ChainSync rollforward event.
     */
    public void updateTip(Tip tip) {
        this.currentTip.set(tip);
        if (tip != null && tip.getPoint() != null) {
            this.tipSlot.set(tip.getPoint().getSlot());
            this.tipHash.set(tip.getPoint().getHash());
        }
        if (tip != null) {
            this.tipBlockNumber.set(tip.getBlock());
        }
    }

    /**
     * Update tip from block number, slot, and hash directly.
     */
    public void updateTip(long blockNumber, long slot, String hash) {
        this.tipBlockNumber.set(blockNumber);
        this.tipSlot.set(slot);
        this.tipHash.set(hash);
    }

    public long getTipBlockNumber() {
        return tipBlockNumber.get();
    }

    public long getTipSlot() {
        return tipSlot.get();
    }

    public String getTipHash() {
        return tipHash.get();
    }

    public Optional<Tip> getCurrentTip() {
        return Optional.ofNullable(currentTip.get());
    }

    /**
     * Mark this peer as connected.
     */
    public void markConnected() {
        this.connected = true;
        this.connectedAt = Instant.now();
        this.failureCount.set(0);
    }

    /**
     * Mark this peer as disconnected and increment failure count.
     */
    public void markDisconnected() {
        this.connected = false;
    }

    public boolean isConnected() {
        return connected && peerClient != null && peerClient.isRunning();
    }

    /**
     * Record a failure (e.g., connection timeout, error).
     */
    public int recordFailure() {
        return failureCount.incrementAndGet();
    }

    public int getFailureCount() {
        return failureCount.get();
    }

    /**
     * Update measured latency to this peer.
     */
    public void updateLatency(long ms) {
        this.latencyMs.set(ms);
    }

    public long getLatencyMs() {
        return latencyMs.get();
    }

    /**
     * Stop the underlying PeerClient connection.
     */
    public void stop() {
        connected = false;
        if (peerClient != null) {
            try {
                peerClient.stop();
            } catch (Exception e) {
                log.warn("Error stopping peer client for {}: {}", peerId, e.getMessage());
            }
            peerClient = null;
        }
    }

    @Override
    public String toString() {
        return String.format("PeerConnection{%s, type=%s, connected=%s, tip=%d/%d}",
                peerId, peerType, connected, tipBlockNumber.get(), tipSlot.get());
    }
}
