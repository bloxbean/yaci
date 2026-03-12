package com.bloxbean.cardano.yaci.core.protocol.appmsg.n2c.notify;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2c.notify.messages.*;
import lombok.extern.slf4j.Slf4j;

/**
 * Client-side agent for Protocol 102 (Local App Message Notification).
 * Blocking/non-blocking consumption of messages from the node.
 */
@Slf4j
public class LocalAppMsgNotifyAgent extends Agent<LocalAppMsgNotifyListener> {
    private boolean shutDown;
    private boolean useBlocking;

    public LocalAppMsgNotifyAgent() {
        this(true);
    }

    public LocalAppMsgNotifyAgent(boolean useBlocking) {
        super(true); // Client agent
        this.useBlocking = useBlocking;
        this.currentState = LocalAppMsgNotifyState.Idle;
    }

    @Override
    public int getProtocolId() {
        return 102;
    }

    @Override
    public boolean isDone() {
        return currentState == LocalAppMsgNotifyState.Done;
    }

    @Override
    protected Message buildNextMessage() {
        if (shutDown)
            return new MsgClientDone();

        if (currentState == LocalAppMsgNotifyState.Idle) {
            return new MsgRequestMessages(useBlocking);
        }
        return null;
    }

    @Override
    protected void processResponse(Message message) {
        if (message == null) return;

        if (message instanceof MsgReplyMessagesNonBlocking) {
            var reply = (MsgReplyMessagesNonBlocking) message;
            log.debug("Received {} messages (non-blocking, hasMore={})",
                    reply.getMessages().size(), reply.isHasMore());
            getAgentListeners().forEach(l ->
                    l.onMessagesReceived(reply.getMessages(), reply.isHasMore()));
        } else if (message instanceof MsgReplyMessagesBlocking) {
            var reply = (MsgReplyMessagesBlocking) message;
            log.debug("Received {} messages (blocking)", reply.getMessages().size());
            getAgentListeners().forEach(l ->
                    l.onMessagesReceived(reply.getMessages(), false));
        }
    }

    public void setUseBlocking(boolean useBlocking) {
        this.useBlocking = useBlocking;
    }

    @Override
    public void reset() {
        this.currentState = LocalAppMsgNotifyState.Idle;
    }

    public void shutdown() {
        this.shutDown = true;
    }
}
