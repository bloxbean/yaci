package com.bloxbean.cardano.yaci.core.protocol.handshake;

import com.bloxbean.cardano.yaci.core.protocol.AgentListener;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.Reason;

public interface HandshakeAgentListener extends AgentListener {

    default void handshakeOk() {

    }

    default void handshakeError(Reason reason) {

    }
}
