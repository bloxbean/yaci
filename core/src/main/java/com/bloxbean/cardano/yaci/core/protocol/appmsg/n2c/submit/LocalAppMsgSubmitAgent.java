package com.bloxbean.cardano.yaci.core.protocol.appmsg.n2c.submit;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2c.submit.messages.*;
import lombok.extern.slf4j.Slf4j;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Client-side agent for Protocol 101 (Local App Message Submit).
 * Simple request-response: submit a message, get accept/reject.
 */
@Slf4j
public class LocalAppMsgSubmitAgent extends Agent<LocalAppMsgSubmitListener> {
    private boolean shutDown;
    private final Queue<AppMessage> submitQueue;
    private final Queue<AppMessage> pendingQueue;

    public LocalAppMsgSubmitAgent() {
        super(true);
        this.submitQueue = new ConcurrentLinkedQueue<>();
        this.pendingQueue = new ConcurrentLinkedQueue<>();
        this.currentState = LocalAppMsgSubmitState.Idle;
    }

    @Override
    public int getProtocolId() {
        return 101;
    }

    @Override
    public boolean isDone() {
        return currentState == LocalAppMsgSubmitState.Done;
    }

    @Override
    protected synchronized Message buildNextMessage() {
        if (shutDown)
            return new MsgDone();

        if (currentState == LocalAppMsgSubmitState.Idle) {
            AppMessage msg = submitQueue.poll();
            if (msg != null) {
                pendingQueue.add(msg);
                return new MsgSubmitMessage(msg);
            } else if (shutDown) {
                return new MsgDone();
            }
        }
        return null;
    }

    @Override
    protected synchronized void processResponse(Message message) {
        if (message == null) return;

        AppMessage pending = pendingQueue.poll();

        if (message instanceof MsgAcceptMessage) {
            log.debug("Message accepted");
            getAgentListeners().forEach(l -> l.messageAccepted(pending, (MsgAcceptMessage) message));
        } else if (message instanceof MsgRejectMessage) {
            MsgRejectMessage reject = (MsgRejectMessage) message;
            log.debug("Message rejected: {} - {}", reject.getReason(), reject.getDetail());
            getAgentListeners().forEach(l -> l.messageRejected(pending, reject));
        }
    }

    public void submitMessage(AppMessage message) {
        submitQueue.add(message);
    }

    @Override
    public synchronized void reset() {
        this.currentState = LocalAppMsgSubmitState.Idle;
        this.submitQueue.clear();
        this.pendingQueue.clear();
    }

    public void shutdown() {
        this.shutDown = true;
    }
}
