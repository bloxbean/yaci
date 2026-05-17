package com.bloxbean.cardano.yaci.core.protocol.appmsg.n2c.notify;

import com.bloxbean.cardano.yaci.core.protocol.AgentListener;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;

import java.util.List;

public interface LocalAppMsgNotifyListener extends AgentListener {
    default void onMessagesReceived(List<AppMessage> messages, boolean hasMore) {}
}
