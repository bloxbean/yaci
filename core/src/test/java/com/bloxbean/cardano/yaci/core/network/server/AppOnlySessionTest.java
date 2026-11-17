package com.bloxbean.cardano.yaci.core.network.server;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.appchainsync.AppChainSyncServerAgent;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgSubmissionConfig;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgSubmissionServerAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.ProposedVersions;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.Refuse;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AppOnlySessionTest {
    private static final long MAGIC = 424242;

    @Test
    void onlyAppAndKeepAliveAgentsAreCreatedWithoutChainState() {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            NodeServerSession session = new NodeServerSession(channel,
                    N2NVersionTableConstant.appOnly(MAGIC, false), null, null, null,
                    List.of(() -> new AppMsgSubmissionServerAgent(AppMsgSubmissionConfig.builder().build()),
                            () -> new AppChainSyncServerAgent((chain, from, to) ->
                                    new AppChainSyncServerAgent.BlockRangeProvider.Range(List.of(), 0))),
                    null, true);
            assertEquals(List.of(8, 100, 103), Arrays.stream(session.getAgents())
                    .map(Agent::getProtocolId).toList());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void appOnlyHandshakeDoesNotFallBackToHybridOrCardanoOrAnotherNetwork() {
        assertAccepted(N2NVersionTableConstant.appOnly(MAGIC, true));
        assertRefused(N2NVersionTableConstant.v11AndAboveWithAppLayer(MAGIC));
        assertRefused(N2NVersionTableConstant.v11AndAbove(MAGIC));
        assertRefused(N2NVersionTableConstant.appOnly(MAGIC + 1, true));
        HandshakeAgent hybrid = new HandshakeAgent(
                N2NVersionTableConstant.v11AndAboveWithAppLayer(MAGIC), false);
        hybrid.receiveResponse(new ProposedVersions(N2NVersionTableConstant.appOnly(MAGIC, true)));
        assertInstanceOf(Refuse.class, hybrid.buildNextMessage());
    }

    @Test
    void failedAndDuplicateFactoriesCannotPublishAPartialSession() {
        EmbeddedChannel channel = new EmbeddedChannel();
        AgentFactory factory = () -> new AppMsgSubmissionServerAgent(AppMsgSubmissionConfig.builder().build());
        try {
            assertThrows(IllegalArgumentException.class, () -> new NodeServerSession(channel,
                    N2NVersionTableConstant.appOnly(MAGIC, false), null, null, null,
                    List.of(factory, factory), null, true));
            assertThrows(IllegalStateException.class, () -> new NodeServerSession(channel,
                    N2NVersionTableConstant.appOnly(MAGIC, false), null, null, null,
                    List.of(() -> { throw new IllegalStateException("factory failed"); }), null, true));
            assertThrows(IllegalArgumentException.class, () -> new NodeServerSession(channel,
                    N2NVersionTableConstant.v11AndAboveWithAppLayer(MAGIC), null, null, null,
                    List.of(factory), null, true));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private void assertAccepted(VersionTable proposed) {
        HandshakeAgent server = new HandshakeAgent(N2NVersionTableConstant.appOnly(MAGIC, false), false);
        server.receiveResponse(new ProposedVersions(proposed));
        AcceptVersion accepted = assertInstanceOf(AcceptVersion.class, server.buildNextMessage());
        assertEquals(101, accepted.getVersionNumber());
    }

    private void assertRefused(VersionTable proposed) {
        HandshakeAgent server = new HandshakeAgent(N2NVersionTableConstant.appOnly(MAGIC, false), false);
        server.receiveResponse(new ProposedVersions(proposed));
        assertInstanceOf(Refuse.class, server.buildNextMessage());
    }
}
