package com.bloxbean.cardano.yaci.core.protocol.txsubmission;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges.*;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class TxSubmissionAgent extends Agent<TxSubmissionListener> {
    private final Map<String, byte[]> txs;
    /**
     * Is the queue of TX received from client
     */
    private final List<String> pendingTxIds;
    /**
     * It's the temporary list of TX ids requested from Server
     */
    private final List<String> requestedTxIds;

    public TxSubmissionAgent() {
        this.currenState = TxSubmissionState.Init;
        txs = new HashMap<>();
        pendingTxIds = new ArrayList<>();
        requestedTxIds = new ArrayList<>();
    }

    @Override
    public int getProtocolId() {
        return 4;
    }

    public void sendNextMessage() {
        log.info("sendNextMessage");
        super.sendNextMessage();
    }


    @Override
    public Message buildNextMessage() {
        log.info("state: {}", currenState);
        switch ((TxSubmissionState) currenState) {
            case Init:
                return new Init();
            case TxIdsNonBlocking:
            case TxIdsBlocking:
                var replyTxIds = getReplyTxIds();
                log.info("retrieving txIds: {}", replyTxIds);
                return replyTxIds;
            case Txs:
                return getReplyTxs();
            default:
                return null;
        }
    }

    private ReplyTxIds getReplyTxIds() {
        if (!txs.isEmpty()) {
            ReplyTxIds replyTxIds = new ReplyTxIds();
            txs.forEach((id, txBytes) -> replyTxIds.addTxId(id, txBytes.length));
            return replyTxIds;
        } else
            return new ReplyTxIds();
    }

    private ReplyTxs getReplyTxs() {
        if (requestedTxIds.isEmpty())
            return new ReplyTxs();

        ReplyTxs replyTxs = new ReplyTxs();
        for (String txId : requestedTxIds) {
            byte[] tx = txs.remove(txId);
            replyTxs.addTx(tx);
        }
        // Ids of requested TXs don't seem to be acked from server.
        // Removing them right away now.
        requestedTxIds.forEach(pendingTxIds::remove);

        return replyTxs;
    }

    @Override
    public void processResponse(Message message) {
        if (message == null) return;

        if (message instanceof Init) {
            log.warn("init");
        } else if (message instanceof RequestTxIds) {
            if (((RequestTxIds) message).isBlocking()) {
                handleRequestTxIdsBlocking((RequestTxIds) message);
            } else {
                handleRequestTxIdsNonBlocking((RequestTxIds) message);
            }
        } else if (message instanceof RequestTxs) {
            handleRequestTxs((RequestTxs) message);
        }
    }

    private void handleRequestTxs(RequestTxs requestTxs) {
        log.info("RequestTxs >>" + requestTxs);
        requestedTxIds.clear();
        requestedTxIds.addAll(requestTxs.getTxIds());
        getAgentListeners().forEach(listener -> listener.handleRequestTxs(requestTxs));
    }

    private void handleRequestTxIdsNonBlocking(RequestTxIds requestTxIds) {
        // process ack
        log.info("RequestTxIdsNonBlocking >> " + requestTxIds);
        removeAcknowledgedTxs(requestTxIds.getAckTxIds());
        addTxToQueue(requestTxIds.getReqTxIds());
        getAgentListeners().forEach(listener -> listener.handleRequestTxIdsNonBlocking(requestTxIds));
    }

    private void handleRequestTxIdsBlocking(RequestTxIds requestTxIds) {
        // process ack
        log.info("RequestTxIdsBlocking >> " + requestTxIds);
        removeAcknowledgedTxs(requestTxIds.getAckTxIds());
        addTxToQueue(requestTxIds.getReqTxIds());
        getAgentListeners().forEach(listener -> listener.handleRequestTxIdsBlocking(requestTxIds));
    }

    private void addTxToQueue(int numTxToAdd) {
        log.info("numTxToAdd: {}", numTxToAdd);
        if (!txs.isEmpty()) {
            txs.keySet()
                    .stream()
                    .filter(txHash -> !pendingTxIds.contains(txHash))
                    .limit(numTxToAdd)
                    .forEach(pendingTxIds::add);
        } else {
            log.info("Nothing to do, txs is empty");
        }
    }

    private void removeAcknowledgedTxs(int numAcknowledgedTransactions) {
        if (numAcknowledgedTransactions > 0) {
            var numTxToRemove = Math.min(numAcknowledgedTransactions, pendingTxIds.size());
            var ackedTxIds = pendingTxIds.subList(0, numTxToRemove);
            ackedTxIds.forEach(txHash -> {
                // remove from map
                txs.remove(txHash);
                // removed from queue
                pendingTxIds.remove(txHash);
            });
        }

    }

    public void enqueueTransaction(String txHash, byte[] txBytes) {
        txs.put(txHash, txBytes);
        if (TxSubmissionState.TxIdsBlocking.equals(currenState)) {
            addTxToQueue(1);
            this.sendNextMessage();
        }
    }

    public boolean hasPendingTx() {
        return !txs.isEmpty();
    }

    @Override
    public boolean isDone() {
        return this.currenState == TxSubmissionState.Done;
    }

    @Override
    public void reset() {
        this.currenState = TxSubmissionState.Init;
    }
}
