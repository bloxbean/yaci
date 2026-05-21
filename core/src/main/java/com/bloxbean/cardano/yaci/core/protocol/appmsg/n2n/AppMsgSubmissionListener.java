package com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n;

import com.bloxbean.cardano.yaci.core.protocol.AgentListener;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.messages.*;

public interface AppMsgSubmissionListener extends AgentListener {

    // Client-side methods (handling requests from server)
    default void handleRequestMessageIds(MsgRequestMessageIds request) {}
    default void handleRequestMessages(MsgRequestMessages request) {}

    // Server-side methods (handling replies from client)
    default void handleReplyMessageIds(MsgReplyMessageIds reply) {}
    default void handleReplyMessages(MsgReplyMessages reply) {}
}
