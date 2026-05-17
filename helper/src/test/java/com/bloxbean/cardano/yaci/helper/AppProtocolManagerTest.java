package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgSubmissionAgent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
}
