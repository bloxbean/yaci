package com.bloxbean.cardano.yaci.node.runtime.peer;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.peersharing.messages.PeerAddress;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.api.EventMetadata;
import com.bloxbean.cardano.yaci.events.api.PublishOptions;
import com.bloxbean.cardano.yaci.helper.PeerClient;
import com.bloxbean.cardano.yaci.helper.PeerDiscovery;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import com.bloxbean.cardano.yaci.node.api.config.PeerType;
import com.bloxbean.cardano.yaci.node.api.config.UpstreamConfig;
import com.bloxbean.cardano.yaci.node.api.events.PeerConnectedEvent;
import com.bloxbean.cardano.yaci.node.runtime.sync.HeaderFanIn;
import com.bloxbean.cardano.yaci.node.runtime.sync.MultiPeerHeaderListener;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

/**
 * Periodic service that discovers new peers via the PeerSharing protocol
 * and adds them to the {@link PeerPool}.
 * <p>
 * Uses the existing {@link PeerDiscovery} helper to query connected peers
 * for their known peers. Discovered peers are deduplicated against the pool,
 * connected as header-only peers (via {@link MultiPeerHeaderListener}),
 * and added to the pool up to a configurable maximum.
 */
@Slf4j
public class PeerDiscoveryService implements AutoCloseable {

    private final PeerPool peerPool;
    private final HeaderFanIn headerFanIn;
    private final EventBus eventBus;
    private final long protocolMagic;
    private final int maxPeers;
    private final int intervalSeconds;
    private final List<PeerClient> peerClients; // shared list from YaciNode

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "peer-discovery");
        t.setDaemon(true);
        return t;
    });

    // Track peers we've already attempted (to avoid retrying failed connections every cycle)
    private final Set<String> attemptedPeers = ConcurrentHashMap.newKeySet();
    private volatile ScheduledFuture<?> task;
    private volatile Point currentStartPoint;
    private volatile boolean running;

    public PeerDiscoveryService(PeerPool peerPool,
                                HeaderFanIn headerFanIn,
                                EventBus eventBus,
                                List<PeerClient> peerClients,
                                long protocolMagic,
                                int maxPeers,
                                int intervalSeconds) {
        this.peerPool = peerPool;
        this.headerFanIn = headerFanIn;
        this.eventBus = eventBus;
        this.peerClients = peerClients;
        this.protocolMagic = protocolMagic;
        this.maxPeers = maxPeers;
        this.intervalSeconds = intervalSeconds;
    }

    /**
     * Start periodic peer discovery.
     *
     * @param startPoint the ChainSync start point for newly connected peers
     */
    public void start(Point startPoint) {
        this.currentStartPoint = startPoint;
        this.running = true;

        // Initial delay of one interval to let existing peers stabilize
        task = scheduler.scheduleWithFixedDelay(
                this::discoverPeers,
                intervalSeconds,
                intervalSeconds,
                TimeUnit.SECONDS
        );

        log.info("PeerDiscoveryService started (interval={}s, maxPeers={})", intervalSeconds, maxPeers);
    }

    /**
     * Update the start point for newly discovered peers (e.g., after sync progresses).
     */
    public void updateStartPoint(Point startPoint) {
        this.currentStartPoint = startPoint;
    }

    /**
     * Single discovery cycle: query each connected peer for its known peers.
     */
    private void discoverPeers() {
        if (!running) return;

        int currentCount = peerPool.size();
        if (currentCount >= maxPeers) {
            log.debug("Peer pool at capacity ({}/{}), skipping discovery", currentCount, maxPeers);
            return;
        }

        int slotsAvailable = maxPeers - currentCount;
        log.debug("Running peer discovery cycle (current={}, max={}, available={})",
                currentCount, maxPeers, slotsAvailable);

        // Query each connected peer for its known peers
        for (PeerConnection conn : peerPool.getHotPeers()) {
            if (!running || peerPool.size() >= maxPeers) break;

            try {
                queryPeerForSharing(conn, slotsAvailable);
            } catch (Exception e) {
                log.debug("Peer discovery failed for {}: {}", conn.getPeerId(), e.getMessage());
            }
        }
    }

    /**
     * Query a single connected peer for its known peers via PeerSharing protocol.
     */
    private void queryPeerForSharing(PeerConnection conn, int maxToAdd) {
        String host = conn.getConfig().getHost();
        int port = conn.getConfig().getPort();
        int requestAmount = Math.min(maxToAdd, 10); // request up to 10 at a time

        PeerDiscovery discovery = new PeerDiscovery(host, port, protocolMagic, requestAmount);
        try {
            List<PeerAddress> discovered = discovery.discover()
                    .block(Duration.ofSeconds(10));

            if (discovered == null || discovered.isEmpty()) {
                log.debug("No peers discovered from {}", conn.getPeerId());
                return;
            }

            log.info("Discovered {} peer(s) from {}", discovered.size(), conn.getPeerId());

            for (PeerAddress addr : discovered) {
                if (!running || peerPool.size() >= maxPeers) break;

                String candidateId = addr.getAddress() + ":" + addr.getPort();

                // Skip if already in pool or already attempted
                if (peerPool.getPeer(candidateId).isPresent()) continue;
                if (attemptedPeers.contains(candidateId)) continue;

                attemptedPeers.add(candidateId);
                connectDiscoveredPeer(addr);
            }
        } catch (Exception e) {
            log.debug("PeerSharing query to {} failed: {}", conn.getPeerId(), e.getMessage());
        } finally {
            discovery.shutdown();
        }
    }

    /**
     * Connect a newly discovered peer and add it to the pool.
     */
    private void connectDiscoveredPeer(PeerAddress addr) {
        String host = addr.getAddress();
        int port = addr.getPort();
        String peerId = host + ":" + port;

        UpstreamConfig upstreamConfig = UpstreamConfig.builder()
                .host(host)
                .port(port)
                .type(PeerType.CARDANO) // discovered peers are always L1 Cardano nodes
                .build();

        try {
            Point startPoint = currentStartPoint;
            if (startPoint == null) startPoint = Point.ORIGIN;

            // Create PeerClient and connect with header-only listener
            PeerClient pc = new PeerClient(host, port, protocolMagic, startPoint);

            BlockChainDataListener listener = new MultiPeerHeaderListener(
                    peerId, headerFanIn, peerPool, eventBus);

            pc.connect(listener, null);
            pc.startHeaderSync(startPoint, true);

            // Add to pool and shared peer client list
            PeerConnection conn = peerPool.addPeer(upstreamConfig);
            conn.markConnected();
            peerClients.add(pc);

            EventMetadata meta = EventMetadata.builder().origin("peer-discovery").build();
            eventBus.publish(
                    new PeerConnectedEvent(peerId, host, port, PeerType.CARDANO),
                    meta, PublishOptions.builder().build());

            log.info("Discovered peer connected: {} (total peers: {})", peerId, peerPool.size());
        } catch (Exception e) {
            log.debug("Failed to connect discovered peer {}: {}", peerId, e.getMessage());
        }
    }

    /**
     * Clear the attempted-peers cache so failed peers can be retried.
     */
    public void resetAttemptedPeers() {
        attemptedPeers.clear();
    }

    public boolean isRunning() {
        return running;
    }

    @Override
    public void close() {
        running = false;
        if (task != null) {
            task.cancel(false);
        }
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("PeerDiscoveryService stopped");
    }
}
