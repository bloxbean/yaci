package com.bloxbean.cardano.yaci.core.protocol.keepalive;

import com.bloxbean.cardano.yaci.core.protocol.AgentListener;
import com.bloxbean.cardano.yaci.core.protocol.keepalive.messages.MsgKeepAlive;
import com.bloxbean.cardano.yaci.core.protocol.keepalive.messages.MsgKeepAliveResponse;

public interface KeepAliveListener extends AgentListener {
    void keepAlive(MsgKeepAlive keepAlive);

    void keepAliveResponse(MsgKeepAliveResponse keepAliveResponse);
}
