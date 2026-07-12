package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgSubmissionAgent;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppProtocolManagerTest {

    @Test
    void getAgentsReturnsEmptyUntilAppMsgIsEnabled() {
        AppProtocolManager manager = new AppProtocolManager();

        assertThat(manager.isAppMsgEnabled()).isFalse();
        assertThat(manager.getAgents()).isEmpty();
    }

    @Test
    void enableAppMsgExposesAppMsgAgent() {
        AppProtocolManager manager = new AppProtocolManager();

        manager.enableAppMsg();

        List<Agent<?>> agents = manager.getAgents();
        AppMsgSubmissionAgent appMsgAgent = manager.getAppMsgSubmissionAgent();

        assertThat(manager.isAppMsgEnabled()).isTrue();
        assertThat(agents).containsExactly(appMsgAgent);
    }

    @Test
    void enabledAppMsgRequiresAppLayerVersionTable() {
        AppProtocolManager manager = new AppProtocolManager();
        manager.enableAppMsg();

        assertThatThrownBy(() -> new N2NPeerFetcher(
                "localhost",
                1,
                Point.ORIGIN,
                N2NVersionTableConstant.v11AndAbove(42),
                manager))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V100");
    }

    @Test
    void enableAppMsgOnFetcherAddsAppLayerVersion() {
        N2NPeerFetcher fetcher = new N2NPeerFetcher(
                "localhost",
                1,
                Point.ORIGIN,
                N2NVersionTableConstant.v11AndAbove(42));

        fetcher.enableAppMsg();

        assertThat(fetcher.getAppProtocolManager().isAppMsgEnabled()).isTrue();
    }

    @Test
    void appChainSyncAgentIsNullUntilEnabled() {
        AppProtocolManager manager = new AppProtocolManager();

        assertThat(manager.isAppChainSyncEnabled()).isFalse();
        assertThat(manager.getAppChainSyncAgent()).isNull();
        assertThat(manager.getAgents()).isEmpty();
    }

    @Test
    void enableAppChainSyncExposesSyncAgent() {
        AppProtocolManager manager = new AppProtocolManager();

        manager.enableAppChainSync();

        assertThat(manager.isAppChainSyncEnabled()).isTrue();
        assertThat(manager.getAppChainSyncAgent()).isNotNull();
        assertThat(manager.getAppChainSyncAgent().getProtocolId()).isEqualTo(103);
        assertThat(manager.getAgents()).containsExactly(manager.getAppChainSyncAgent());
    }

    @Test
    void bothProtocolsEnabledRideTheSameConnection() {
        AppProtocolManager manager = new AppProtocolManager();

        manager.enableAppMsg();
        manager.enableAppChainSync();

        assertThat(manager.getAgents()).containsExactly(
                manager.getAppMsgSubmissionAgent(),
                manager.getAppChainSyncAgent());
    }

    @Test
    void enabledAppChainSyncRequiresAppLayerVersionTable() {
        AppProtocolManager manager = new AppProtocolManager();
        manager.enableAppChainSync();

        assertThatThrownBy(() -> new N2NPeerFetcher(
                "localhost",
                1,
                Point.ORIGIN,
                N2NVersionTableConstant.v11AndAbove(42),
                manager))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V100");
    }

    @Test
    void enableAppChainSyncOnFetcherAddsAppLayerVersion() {
        N2NPeerFetcher fetcher = new N2NPeerFetcher(
                "localhost",
                1,
                Point.ORIGIN,
                N2NVersionTableConstant.v11AndAbove(42));

        fetcher.enableAppChainSync();

        assertThat(fetcher.getAppProtocolManager().isAppChainSyncEnabled()).isTrue();
    }

    @Test
    void appLayerNegotiatedOnlyAfterV100Handshake() {
        AppProtocolManager manager = new AppProtocolManager();
        manager.enableAppChainSync();

        assertThat(manager.isAppLayerNegotiated()).isFalse();

        manager.onHandshakeComplete(acceptVersion(11));
        assertThat(manager.isAppLayerNegotiated()).isFalse();

        manager.onHandshakeComplete(acceptVersion(100));
        assertThat(manager.isAppLayerNegotiated()).isTrue();
    }

    @Test
    void enableAppMsgWithConfigPreservesEnabledAppChainSync() {
        // enableAppMsg(config) replaces the manager — an earlier
        // enableAppChainSync() must survive (call-order independence)
        PeerClient peerClient = new PeerClient("localhost", 1, 42, Point.ORIGIN);

        peerClient.enableAppChainSync();
        peerClient.enableAppMsg(
                com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgSubmissionConfig.createDefault());

        assertThat(peerClient.getAppProtocolManager().isAppMsgEnabled()).isTrue();
        assertThat(peerClient.getAppProtocolManager().isAppChainSyncEnabled()).isTrue();
        assertThat(peerClient.getAppProtocolManager().getAgents()).hasSize(2);
    }

    @Test
    void handshakeWithNothingEnabledDoesNotMarkNegotiated() {
        AppProtocolManager manager = new AppProtocolManager();

        manager.onHandshakeComplete(acceptVersion(100));

        assertThat(manager.isAppLayerNegotiated()).isFalse();
    }

    private static com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion acceptVersion(long version) {
        return new com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion(
                version, N2NVersionTableConstant.v11AndAbove(42).getVersionDataMap()
                        .values().iterator().next());
    }
}
