package com.bloxbean.cardano.yaci.core.protocol.txsubmission;

import com.bloxbean.cardano.yaci.core.protocol.AgentListener;
import com.bloxbean.cardano.yaci.core.protocol.keepalive.messages.MsgKeepAliveResponse;

public interface TxSubmissionListener extends AgentListener {
    void keepAliveResponse(MsgKeepAliveResponse response);
}
