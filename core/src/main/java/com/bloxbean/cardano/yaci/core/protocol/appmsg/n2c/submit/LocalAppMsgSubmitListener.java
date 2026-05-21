package com.bloxbean.cardano.yaci.core.protocol.appmsg.n2c.submit;

import com.bloxbean.cardano.yaci.core.protocol.AgentListener;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2c.submit.messages.MsgAcceptMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2c.submit.messages.MsgRejectMessage;

public interface LocalAppMsgSubmitListener extends AgentListener {
    default void messageAccepted(AppMessage message, MsgAcceptMessage accept) {}
    default void messageRejected(AppMessage message, MsgRejectMessage reject) {}
}
