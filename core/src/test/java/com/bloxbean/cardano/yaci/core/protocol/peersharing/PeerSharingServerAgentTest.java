package com.bloxbean.cardano.yaci.core.protocol.peersharing;

import com.bloxbean.cardano.yaci.core.protocol.peersharing.messages.MsgSharePeers;
import com.bloxbean.cardano.yaci.core.protocol.peersharing.messages.MsgShareRequest;
import com.bloxbean.cardano.yaci.core.protocol.peersharing.messages.PeerAddress;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PeerSharingServerAgentTest {

    @Test
    void sharesAtMostRequestedPeers() {
        PeerSharingServerAgent agent = new PeerSharingServerAgent(amount -> List.of(
                PeerAddress.ipv4("10.0.0.1", 3001),
                PeerAddress.ipv4("10.0.0.2", 3001),
                PeerAddress.ipv4("10.0.0.3", 3001)));

        agent.receiveResponse(new MsgShareRequest(2));

        MsgSharePeers response = (MsgSharePeers) agent.buildNextMessage();
        assertEquals(2, response.getPeerAddresses().size());
        assertEquals("10.0.0.1", response.getPeerAddresses().get(0).getAddress());
        assertEquals("10.0.0.2", response.getPeerAddresses().get(1).getAddress());
    }

    @Test
    void clampsOversizedRequestToProtocolLimit() {
        PeerSharingServerAgent agent = new PeerSharingServerAgent(amount -> {
            assertEquals(PeerSharingAgent.MAX_REQUEST_AMOUNT, amount);
            return List.of(PeerAddress.ipv4("10.0.0.1", 3001));
        });

        agent.receiveResponse(new MsgShareRequest(255));

        MsgSharePeers response = (MsgSharePeers) agent.buildNextMessage();
        assertEquals(1, response.getPeerAddresses().size());
    }
}
