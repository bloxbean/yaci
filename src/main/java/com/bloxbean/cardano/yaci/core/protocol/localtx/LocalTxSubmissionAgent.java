package com.bloxbean.cardano.yaci.core.protocol.localtx;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.localtx.messages.MsgAcceptTx;
import com.bloxbean.cardano.yaci.core.protocol.localtx.messages.MsgDone;
import com.bloxbean.cardano.yaci.core.protocol.localtx.messages.MsgRejectTx;
import com.bloxbean.cardano.yaci.core.protocol.localtx.messages.MsgSubmitTx;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.PriorityQueue;
import java.util.Queue;

@Slf4j
public class LocalTxSubmissionAgent extends Agent {
    private boolean shutDown;
    private Queue<Txn> txnQueue;

    public LocalTxSubmissionAgent() {
        txnQueue = new PriorityQueue<>();
        this.currenState = LocalTxSubmissionState.Idle;
    }

    @Override
    public int getProtocolId() {
        return 6;
    }

    @Override
    public boolean isDone() {
        return this.currenState == LocalTxSubmissionState.Done;
    }

    @Override
    protected Message buildNextMessage() {
        if (shutDown)
            return new MsgDone();

        switch ((LocalTxSubmissionState) currenState) {
            case Idle:
                if (txnQueue.peek() != null) {
                    if (log.isDebugEnabled())
                        log.debug("Found txn in txn queue : {}", txnQueue.peek());
                    Txn txn = txnQueue.poll();
                    return new MsgSubmitTx(txn.txnBytes);
                } else if (shutDown) {
                    if (log.isDebugEnabled())
                        log.debug("MsgDone");
                    return new MsgDone();
                } else {
                    if (log.isDebugEnabled())
                        log.debug("Idle state, but no message to send");
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

        if (message instanceof MsgAcceptTx) {
            if (log.isDebugEnabled())
                log.debug("MsgAccept : {}", message);
        } else if (message instanceof MsgRejectTx) {
            if (log.isDebugEnabled())
                log.debug("MsgRject : {}", message);
        } else {
            if (log.isDebugEnabled())
                log.debug("Unknown messaget type : {}", message);
        }
    }

    public void submitTx(Txn txn) {
        txnQueue.add(txn);
    }

    public void shutdown() {
        this.shutDown = true;
    }

    @AllArgsConstructor
    @Getter
    public static class  Txn {
        private String txnHash;
        private byte[] txnBytes;
    }
}
