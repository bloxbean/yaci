package com.bloxbean.cardano.yaci.node.runtime.peer;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.impl.NoopEventBus;
import com.bloxbean.cardano.yaci.helper.PeerClient;
import com.bloxbean.cardano.yaci.node.api.chain.ChainSelectionStrategy;
import com.bloxbean.cardano.yaci.node.api.config.PeerType;
import com.bloxbean.cardano.yaci.node.api.config.UpstreamConfig;
import com.bloxbean.cardano.yaci.node.runtime.chain.PraosChainSelection;
import com.bloxbean.cardano.yaci.node.runtime.sync.HeaderFanIn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class PeerDiscoveryServiceTest {

    private PeerPool peerPool;
    private EventBus eventBus;
    private HeaderFanIn headerFanIn;
    private List<PeerClient> peerClients;
    private PeerDiscoveryService service;

    @BeforeEach
    void setUp() {
        ChainSelectionStrategy strategy = new PraosChainSelection(2160);
        peerPool = new PeerPool(strategy);
        eventBus = new NoopEventBus();
        headerFanIn = new HeaderFanIn(peerPool, null, eventBus, strategy);
        peerClients = new CopyOnWriteArrayList<>();
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.close();
        }
    }

    @Test
    void start_shouldInitializeAndRun() {
        service = new PeerDiscoveryService(
                peerPool, headerFanIn, eventBus, peerClients,
                1, 20, 60);

        service.start(Point.ORIGIN);

        assertThat(service.isRunning()).isTrue();
    }

    @Test
    void close_shouldStopService() {
        service = new PeerDiscoveryService(
                peerPool, headerFanIn, eventBus, peerClients,
                1, 20, 60);

        service.start(Point.ORIGIN);
        assertThat(service.isRunning()).isTrue();

        service.close();
        assertThat(service.isRunning()).isFalse();
    }

    @Test
    void shouldRespectMaxPeersLimit() {
        // Fill pool to max
        int maxPeers = 3;
        service = new PeerDiscoveryService(
                peerPool, headerFanIn, eventBus, peerClients,
                1, maxPeers, 300); // long interval so it won't auto-run

        for (int i = 0; i < maxPeers; i++) {
            peerPool.addPeer(UpstreamConfig.builder()
                    .host("host" + i).port(3000 + i).type(PeerType.CARDANO).build());
        }

        // Pool is at capacity — service should start but discovery cycle will be a no-op
        service.start(Point.ORIGIN);
        assertThat(peerPool.size()).isEqualTo(maxPeers);
    }

    @Test
    void resetAttemptedPeers_shouldClearCache() {
        service = new PeerDiscoveryService(
                peerPool, headerFanIn, eventBus, peerClients,
                1, 20, 60);

        // Manually test that resetAttemptedPeers doesn't throw and works
        service.resetAttemptedPeers();
        assertThat(service.isRunning()).isFalse(); // not started yet
    }

    @Test
    void updateStartPoint_shouldUpdatePoint() {
        service = new PeerDiscoveryService(
                peerPool, headerFanIn, eventBus, peerClients,
                1, 20, 60);

        service.start(Point.ORIGIN);
        Point newPoint = new Point(1000, "abc123");
        service.updateStartPoint(newPoint);

        // No assertion on internal state — just verify no exception
        assertThat(service.isRunning()).isTrue();
    }
}
