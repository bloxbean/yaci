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
}
