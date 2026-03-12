package com.bloxbean.cardano.yaci.node.runtime.sync;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.node.api.config.PeerType;
import com.bloxbean.cardano.yaci.node.api.config.UpstreamConfig;
import com.bloxbean.cardano.yaci.node.runtime.chain.InMemoryChainState;
import com.bloxbean.cardano.yaci.node.runtime.chain.PraosChainSelection;
import com.bloxbean.cardano.yaci.node.runtime.peer.PeerPool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HeaderFanInTest {

    private PeerPool peerPool;
    private InMemoryChainState chainState;
    private HeaderFanIn headerFanIn;

    @BeforeEach
    void setUp() {
        PraosChainSelection strategy = new PraosChainSelection(2160);
        peerPool = new PeerPool(strategy);
        chainState = new InMemoryChainState();
        headerFanIn = new HeaderFanIn(peerPool, chainState, null, strategy);

        // Register two peers
        peerPool.addPeer(UpstreamConfig.builder()
                .host("peer1").port(3001).type(PeerType.CARDANO).build());
        peerPool.addPeer(UpstreamConfig.builder()
                .host("peer2").port(3002).type(PeerType.CARDANO).build());
    }

    @Test
    void firstHeaderAccepted() {
        Tip tip = new Tip(new Point(100, "hash-100"), 10);

        boolean result = headerFanIn.onHeaderReceived("peer1:3001", tip, "hash-100", 10, 100);

        assertThat(result).isTrue();
        assertThat(headerFanIn.getBestPeerId()).isEqualTo("peer1:3001");
        assertThat(headerFanIn.getBestBlockNumber()).isEqualTo(10);
        assertThat(headerFanIn.getBestSlot()).isEqualTo(100);
    }

    @Test
    void duplicateHeaderRejected() {
        Tip tip = new Tip(new Point(100, "hash-100"), 10);

        boolean first = headerFanIn.onHeaderReceived("peer1:3001", tip, "hash-100", 10, 100);
        boolean second = headerFanIn.onHeaderReceived("peer2:3002", tip, "hash-100", 10, 100);

        assertThat(first).isTrue();
        assertThat(second).isFalse(); // duplicate
    }

    @Test
    void higherBlockNumberBecomesBest() {
        Tip tip1 = new Tip(new Point(100, "hash-100"), 10);
        Tip tip2 = new Tip(new Point(200, "hash-200"), 20);

        headerFanIn.onHeaderReceived("peer1:3001", tip1, "hash-100", 10, 100);
        headerFanIn.onHeaderReceived("peer2:3002", tip2, "hash-200", 20, 200);

        assertThat(headerFanIn.getBestPeerId()).isEqualTo("peer2:3002");
        assertThat(headerFanIn.getBestBlockNumber()).isEqualTo(20);
    }

    @Test
    void lowerBlockNumberDoesNotSwitchBest() {
        Tip tip1 = new Tip(new Point(200, "hash-200"), 20);
        Tip tip2 = new Tip(new Point(100, "hash-100"), 10);

        headerFanIn.onHeaderReceived("peer1:3001", tip1, "hash-200", 20, 200);
        headerFanIn.onHeaderReceived("peer2:3002", tip2, "hash-100", 10, 100);

        // peer1 still best
        assertThat(headerFanIn.getBestPeerId()).isEqualTo("peer1:3001");
        assertThat(headerFanIn.getBestBlockNumber()).isEqualTo(20);
    }

    @Test
    void sameBlockNumberTieBreakBySmallestSlot() {
        Tip tip1 = new Tip(new Point(200, "hash-a"), 10);
        Tip tip2 = new Tip(new Point(199, "hash-b"), 10); // same block, smaller slot

        headerFanIn.onHeaderReceived("peer1:3001", tip1, "hash-a", 10, 200);
        headerFanIn.onHeaderReceived("peer2:3002", tip2, "hash-b", 10, 199);

        assertThat(headerFanIn.getBestPeerId()).isEqualTo("peer2:3002");
        assertThat(headerFanIn.getBestSlot()).isEqualTo(199);
    }

    @Test
    void progressiveHeadersFromSamePeer() {
        for (int i = 1; i <= 5; i++) {
            Tip tip = new Tip(new Point(i * 100L, "hash-" + i), i);
            headerFanIn.onHeaderReceived("peer1:3001", tip, "hash-" + i, i, i * 100L);
        }

        assertThat(headerFanIn.getBestBlockNumber()).isEqualTo(5);
        assertThat(headerFanIn.getBestSlot()).isEqualTo(500);
    }
}
