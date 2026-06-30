package com.bloxbean.cardano.yaci.core.protocol.handshake;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.N2NVersionData;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.ProposedVersions;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class HandshakeNegotiationTest {

    @Test
    void serverNegotiatesPeerSharingWhenBothSidesSupportIt() {
        HandshakeAgent server = new HandshakeAgent(
                N2NVersionTableConstant.v11AndAbove(1, false, 1, false),
                false);
        ProposedVersions clientProposal = new ProposedVersions(
                N2NVersionTableConstant.v11AndAbove(1, false, 1, false));

        server.receiveResponse(clientProposal);
        Message response = server.buildNextMessage();

        AcceptVersion accepted = assertInstanceOf(AcceptVersion.class, response);
        N2NVersionData versionData = assertInstanceOf(N2NVersionData.class, accepted.getVersionData());
        assertEquals(1, versionData.getPeerSharing());
    }

    @Test
    void serverDoesNotEnablePeerSharingWhenRemoteDoesNotRequestIt() {
        HandshakeAgent server = new HandshakeAgent(
                N2NVersionTableConstant.v11AndAbove(1, false, 1, false),
                false);
        ProposedVersions clientProposal = new ProposedVersions(
                N2NVersionTableConstant.v11AndAbove(1, false, 0, false));

        server.receiveResponse(clientProposal);
        Message response = server.buildNextMessage();

        AcceptVersion accepted = assertInstanceOf(AcceptVersion.class, response);
        N2NVersionData versionData = assertInstanceOf(N2NVersionData.class, accepted.getVersionData());
        assertEquals(0, versionData.getPeerSharing());
    }

    @Test
    void serverNotifiesHandshakeOkWhenAcceptVersionIsSent() {
        HandshakeAgent server = new HandshakeAgent(
                N2NVersionTableConstant.v11AndAbove(1, false, 1, false),
                false);
        AtomicInteger okCount = new AtomicInteger();
        AtomicReference<AcceptVersion> acceptedVersion = new AtomicReference<>();
        server.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                okCount.incrementAndGet();
                acceptedVersion.set(server.getProtocolVersion());
            }
        });

        server.receiveResponse(new ProposedVersions(
                N2NVersionTableConstant.v11AndAbove(1, false, 1, false)));
        Message response = server.buildNextMessage();
        server.sendRequest(response);

        AcceptVersion accepted = assertInstanceOf(AcceptVersion.class, response);
        assertEquals(1, okCount.get());
        assertEquals(accepted, acceptedVersion.get());
        assertEquals(accepted, server.getProtocolVersion());
    }
}
