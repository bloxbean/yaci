package com.bloxbean.cardano.yaci.core.protocol.localtx;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.localtx.messages.MsgAcceptTx;
import com.bloxbean.cardano.yaci.core.protocol.localtx.messages.MsgDone;
import com.bloxbean.cardano.yaci.core.protocol.localtx.messages.MsgRejectTx;
import com.bloxbean.cardano.yaci.core.protocol.localtx.messages.MsgSubmitTx;
import com.bloxbean.cardano.yaci.core.protocol.localtx.model.TxSubmissionRequest;
import lombok.extern.slf4j.Slf4j;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

@Slf4j
public class LocalTxSubmissionAgent extends Agent<LocalTxSubmissionListener> {
    private boolean shutDown;
    private Queue<TxSubmissionRequest> txnQueue;
    private Queue<TxSubmissionRequest> pendingQueue;

    public LocalTxSubmissionAgent() {
        this(true);
    }
    public LocalTxSubmissionAgent(boolean isClient) {
        super(isClient);
        txnQueue = new ConcurrentLinkedQueue<>();
        pendingQueue = new ConcurrentLinkedQueue<>();
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
    protected synchronized Message buildNextMessage() {
        if (shutDown)
            return new MsgDone();

        switch ((LocalTxSubmissionState) currenState) {
            case Idle:
                if (txnQueue.peek() != null) {
                    if (log.isDebugEnabled())
                        log.debug("Found txn in txn queue : {}", txnQueue.peek());
                    TxSubmissionRequest txSubmissionRequest = txnQueue.poll();
                    pendingQueue.add(txSubmissionRequest);
                    return new MsgSubmitTx(txSubmissionRequest.getTxBodyType(), txSubmissionRequest.getTxnBytes());
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
    protected synchronized void processResponse(Message message) {
        if (message == null) {
            if (log.isDebugEnabled())
                log.debug("Message is null");
        }

        TxSubmissionRequest txSubmissionRequest = null;
        if (!pendingQueue.isEmpty())
            txSubmissionRequest = pendingQueue.poll();

        if (message instanceof MsgAcceptTx) {
            if (log.isDebugEnabled())
                log.debug("MsgAccept : {}", message);
            onTxAccepted(txSubmissionRequest, (MsgAcceptTx) message);
        } else if (message instanceof MsgRejectTx) {
            if (log.isDebugEnabled())
                log.debug("MsgRject : {}", message);
            onTxRejected(txSubmissionRequest, (MsgRejectTx) message);
        } else {
            if (log.isDebugEnabled())
                log.debug("Unknown messaget type : {}", message);
        }
    }

    private void onTxRejected(TxSubmissionRequest txSubmissionRequest, MsgRejectTx message) {
        getAgentListeners().stream().forEach(
                listener -> listener.txRejected(txSubmissionRequest, message)
        );
    }

    private void onTxAccepted(TxSubmissionRequest txSubmissionRequest, MsgAcceptTx message) {
        getAgentListeners().stream().forEach(
                listener -> listener.txAccepted(txSubmissionRequest, message)
        );
    }

    public void submitTx(TxSubmissionRequest txnRequest) {
        txnQueue.add(txnRequest);
    }

    @Override
    public synchronized void reset() {
        this.currenState = LocalTxSubmissionState.Idle;
        this.txnQueue.clear();
        this.pendingQueue.clear();
    }

    public void shutdown() {
        this.shutDown = true;
    }


}
