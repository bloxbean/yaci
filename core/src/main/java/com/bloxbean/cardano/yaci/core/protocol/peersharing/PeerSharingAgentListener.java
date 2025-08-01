package com.bloxbean.cardano.yaci.core.protocol.peersharing;

import com.bloxbean.cardano.yaci.core.protocol.AgentListener;
import com.bloxbean.cardano.yaci.core.protocol.peersharing.messages.PeerAddress;

import java.util.List;

public interface PeerSharingAgentListener extends AgentListener {

    void peersReceived(List<PeerAddress> peerAddresses);

    void protocolCompleted();

    default void error(String error) {
        // Default implementation - can be overridden
    }
}
