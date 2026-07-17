package com.bloxbean.cardano.yaci.core.protocol.localtxmonitor;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.localtx.messages.MsgDone;
import com.bloxbean.cardano.yaci.core.protocol.localtxmonitor.messages.*;
import lombok.extern.slf4j.Slf4j;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static com.bloxbean.cardano.yaci.core.protocol.localtxmonitor.LocalTxMonitorState.Idle;

//https://input-output-hk.github.io/ouroboros-network/ouroboros-network/Ouroboros-Network-Protocol-LocalTxMonitor-Type.html
@Slf4j
public class LocalTxMonitorAgent extends Agent<LocalTxMonitorListener> {
    private boolean shutDown;
    private Queue<Message> acquiredCommands;
    private Queue<MsgQuery> pendingQueryQueue;

    public LocalTxMonitorAgent() {
        this(true);
    }
    public LocalTxMonitorAgent(boolean isClient) {
        super(isClient);
        this.currenState = Idle;
        this.acquiredCommands = new ConcurrentLinkedQueue<>();
        this.pendingQueryQueue = new ConcurrentLinkedQueue<>();
    }

    @Override
    public int getProtocolId() {
        return 9;
    }

    @Override
    public boolean isDone() {
        return currenState == LocalTxMonitorState.Done;
    }

    @Override
    protected Message buildNextMessage() {
        if (shutDown && currenState == Idle) {
            if (log.isDebugEnabled())
                log.debug("Shutdown flag set. MsgDone()");
            return new MsgDone();
        }

        switch ((LocalTxMonitorState) currenState) {
            case Idle:
            case Acquired:
                Message peekMsg = acquiredCommands.peek();
                if (peekMsg != null) {
                    if (log.isDebugEnabled())
                        log.debug("Found command in acquired commands queue : {}", peekMsg);

                    if (peekMsg instanceof MsgQuery)
                        pendingQueryQueue.add((MsgQuery) peekMsg);
                    return acquiredCommands.poll();
                } else {
                    if (log.isDebugEnabled())
                        log.debug("No command found in acquired commands queue.");
                    return null;
                }
            default:
                return null;
        }
    }

    @Override
    protected void processResponse(Message message) {
        if (message == null) {
            if (log.isDebugEnabled())
                log.debug("Message is null");
        }

        MsgQuery query = null;
        if (!pendingQueryQueue.isEmpty())
            query = pendingQueryQueue.poll();

        if (message instanceof MsgAcquired) {
            if (log.isDebugEnabled())
                log.debug("MsgAcquired: {}", message);
            onMessageAcquired((MsgAwaitAcquire) query, (MsgAcquired) message);
        } else if (message instanceof MsgReplyHasTx) {
            onMessageReplyHashTx((MsgHasTx) query, (MsgReplyHasTx) message);
        } else if (message instanceof MsgReplyNextTx) {
            onMessageReplyNextTx((MsgNextTx) query, (MsgReplyNextTx) message);
        } else if (message instanceof MsgReplyGetSizes) {
            onMessageReplyGetSizes((MsgGetSizes) query, (MsgReplyGetSizes) message);
        }

    }

    private void onMessageAcquired(MsgAwaitAcquire msgAwaitAcquire, MsgAcquired msgAcquired) {
        if (log.isDebugEnabled())
            log.debug("onMessageAcquired : {} ", msgAcquired);

        getAgentListeners().stream().forEach(
                listener -> listener.acquiredAt(msgAwaitAcquire, msgAcquired)
        );
    }

    private void onMessageReplyHashTx(MsgHasTx query, MsgReplyHasTx message) {
        if (log.isDebugEnabled())
            log.debug("onMessageReplyHashTx : {} ", message);

        getAgentListeners().stream().forEach(
                listener -> listener.onReplyHashTx(query, message)
        );
    }

    private void onMessageReplyNextTx(MsgNextTx query, MsgReplyNextTx message) {
        if (log.isDebugEnabled())
            log.debug("onMessageReplyNextTx : {} ", message);

        getAgentListeners().stream().forEach(
                listener -> listener.onReplyNextTx(query, message)
        );
    }

    private void onMessageReplyGetSizes(MsgGetSizes query, MsgReplyGetSizes message) {
        if (log.isDebugEnabled())
            log.debug("MsgReplyGetSizes : {} ", message);

        getAgentListeners().stream().forEach(
                listener -> listener.onReplyGetSizes(query, message)
        );
    }

    public MsgAwaitAcquire awaitAcquire() {
        MsgAwaitAcquire awaitAcquire = new MsgAwaitAcquire();
        this.currenState.verifyMessageType(awaitAcquire);
        acquiredCommands.add(awaitAcquire);
        return awaitAcquire;
    }

    public MsgRelease release() {
        MsgRelease msgRelease = new MsgRelease();
        this.currenState.verifyMessageType(msgRelease);
        acquiredCommands.add(msgRelease);
        return msgRelease;
    }

    public MsgHasTx hasTx(String txId) {
        MsgHasTx msgHasTx = new MsgHasTx(txId);
        this.currenState.verifyMessageType(msgHasTx);
        acquiredCommands.add(msgHasTx);
        return msgHasTx;
    }

    public MsgNextTx nextTx() {
        MsgNextTx msgNextTx = new MsgNextTx();
        this.currenState.verifyMessageType(msgNextTx);
        acquiredCommands.add(msgNextTx);
        return msgNextTx;
    }

    public MsgGetSizes getSizeAndCapacity() {
        MsgGetSizes msgGetSizes = new MsgGetSizes();
        this.currenState.verifyMessageType(msgGetSizes);
        acquiredCommands.add(msgGetSizes);
        return msgGetSizes;
    }

    //TODO -- check
    @Override
    public void reset() {
        this.currenState = Idle;
        acquiredCommands.clear();
        pendingQueryQueue.clear();
    }


    @Override
    public void shutdown() {
        this.shutDown = true;
    }
}
