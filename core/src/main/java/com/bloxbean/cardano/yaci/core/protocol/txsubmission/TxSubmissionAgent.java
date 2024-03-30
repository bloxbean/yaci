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
    private final List<String> reqTxIds;

    public TxSubmissionAgent() {
        this.currenState = TxSubmissionState.Init;
        txs = new HashMap<>();
        reqTxIds = new ArrayList<>();
    }

    @Override
    public int getProtocolId() {
        return 4;
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
        if (reqTxIds.isEmpty())
            return new ReplyTxs();

        ReplyTxs replyTxs = new ReplyTxs();
        for (String txId : reqTxIds) {
            byte[] tx = txs.get(txId);
            replyTxs.addTx(tx);
        }

        return replyTxs;
    }

    @Override
    public void processResponse(Message message) {
        if (message == null) return;
        if (message instanceof RequestTxIds) {
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
        // nothing to do here I guess, as ack is sent with next id requests
        log.info("RequestTxs >>" + requestTxs);
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
                    .filter(txHash -> !reqTxIds.contains(txHash))
                    .limit(numTxToAdd)
                    .forEach(reqTxIds::add);
        } else {
            log.info("Nothing to do, txs is empty");
        }
    }

    private void removeAcknowledgedTxs(int numAcknowledgedTransactions) {
        log.info("numAcknowledgedTransactions: {}", numAcknowledgedTransactions);
        if (numAcknowledgedTransactions > 0) {
            var numTxToRemove = Math.min(numAcknowledgedTransactions, reqTxIds.size());
            var ackedTxIds = reqTxIds.subList(0, numTxToRemove);
            ackedTxIds.forEach(txHash -> {
                // remove from map
                txs.remove(txHash);
                // removed from queue
                reqTxIds.remove(txHash);
            });
        }

    }

    public void enqueueTransaction(String txHash, byte[] txBytes) {
        log.info("enqueuing tx: {}", txHash);
        txs.put(txHash, txBytes);
        log.info("num pending tx: {}", txs.size());
        if (TxSubmissionState.TxIdsBlocking.equals(currenState)) {
            log.info("blocking, adding to queue and submitting");
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
