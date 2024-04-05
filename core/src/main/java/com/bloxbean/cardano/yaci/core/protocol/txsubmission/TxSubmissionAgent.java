package com.bloxbean.cardano.yaci.core.protocol.txsubmission;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges.*;
import com.bloxbean.cardano.yaci.core.util.Tuple;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Optional;
import java.util.Vector;

@Slf4j
public class TxSubmissionAgent extends Agent<TxSubmissionListener> {
    // txs should be stored in a thread-safe, ordered (tx dependency/chaining) data structure.
    private final Vector<Tuple<String, byte[]>> txs;
    /**
     * Is the queue of TX received from client
     */
    private final Vector<String> pendingTxIds;
    /**
     * It's the temporary list of TX ids requested from Server
     */
    private final Vector<String> requestedTxIds;

    public TxSubmissionAgent() {
        this.currenState = TxSubmissionState.Init;
        this.txs = new Vector<>();
        this.pendingTxIds = new Vector<>();
        this.requestedTxIds = new Vector<>();
    }

    @Override
    public int getProtocolId() {
        return 4;
    }

    public void sendNextMessage() {
        super.sendNextMessage();
    }

    @Override
    public Message buildNextMessage() {
        switch ((TxSubmissionState) currenState) {
            case Init:
                return new Init();
            case TxIdsNonBlocking:
            case TxIdsBlocking:
                return getReplyTxIds();
            case Txs:
                return getReplyTxs();
            default:
                return null;
        }
    }

    private Optional<Tuple<String, byte[]>> findTxIdAndHash(String id) {
        return txs.stream().filter(txIdAndHash -> txIdAndHash._1.equals(id)).findAny();
    }

    private Optional<byte[]> removeTxIdAndHash(String id) {
        var txIdAndHashOpt = txs.stream().filter(txIdAndHash -> txIdAndHash._1.equals(id)).findAny();
        txIdAndHashOpt.ifPresent(txs::remove);
        return txIdAndHashOpt.map(txIdAndHash -> txIdAndHash._2);
    }

    private ReplyTxIds getReplyTxIds() {
        if (!pendingTxIds.isEmpty()) {
            ReplyTxIds replyTxIds = new ReplyTxIds();
            // Not limiting how many txs to add, as pendingTxIds should be already capped to num of req txs
            pendingTxIds
                    .stream()
                    .flatMap(id -> findTxIdAndHash(id).stream())
                    .forEach(idAndBytes -> replyTxIds.addTxId(idAndBytes._1, idAndBytes._2.length));
            if (log.isDebugEnabled())
                log.debug("TxIds: {}", replyTxIds.getTxIdAndSizeMap().size());
            return replyTxIds;
        } else {
            if (log.isDebugEnabled())
                log.debug("TxIds: 0");
        }
        return new ReplyTxIds();
    }

    private ReplyTxs getReplyTxs() {
        if (requestedTxIds.isEmpty())
            return new ReplyTxs();

        ReplyTxs replyTxs = new ReplyTxs();
        for (String txId : requestedTxIds) {
            removeTxIdAndHash(txId)
                    .ifPresent(replyTxs::addTx);
        }
        // Ids of requested TXs don't seem to be acked from server.
        // Removing them right away now.
        requestedTxIds.forEach(pendingTxIds::remove);
        if (log.isDebugEnabled())
            log.debug("Txs: {}", replyTxs.getTxns().size());
        return replyTxs;
    }

    @Override
    public void processResponse(Message message) {
        if (message == null) return;

        if (message instanceof Init) {
            log.warn("init");
        } else if (message instanceof RequestTxIds) {
            var requestTxIds = (RequestTxIds) message;
            if (requestTxIds.isBlocking()) {
                if (log.isDebugEnabled())
                    log.debug("RequestTxIds - Blocking, ack: {}, req: {}", requestTxIds.getAckTxIds(), requestTxIds.getReqTxIds());
                handleRequestTxIdsBlocking(requestTxIds);
            } else {
                if (log.isDebugEnabled())
                    log.debug("RequestTxIds - NonBlocking, ack: {}, req: {}", requestTxIds.getAckTxIds(), requestTxIds.getReqTxIds());
                handleRequestTxIdsNonBlocking(requestTxIds);
            }
        } else if (message instanceof RequestTxs) {
            handleRequestTxs((RequestTxs) message);
        }
    }

    private void handleRequestTxs(RequestTxs requestTxs) {
        requestedTxIds.clear();
        requestedTxIds.addAll(requestTxs.getTxIds());
        getAgentListeners().forEach(listener -> listener.handleRequestTxs(requestTxs));
    }

    private void handleRequestTxIdsNonBlocking(RequestTxIds requestTxIds) {
        // process ack
        removeAcknowledgedTxs(requestTxIds.getAckTxIds());
        addTxToQueue(requestTxIds.getReqTxIds());
        getAgentListeners().forEach(listener -> listener.handleRequestTxIdsNonBlocking(requestTxIds));
    }

    private void handleRequestTxIdsBlocking(RequestTxIds requestTxIds) {
        // process ack
        removeAcknowledgedTxs(requestTxIds.getAckTxIds());
        addTxToQueue(requestTxIds.getReqTxIds());
        getAgentListeners().forEach(listener -> listener.handleRequestTxIdsBlocking(requestTxIds));
    }

    private void addTxToQueue(int numTxToAdd) {
        // pendingTxIds size can't exceed numTxToAdd
        var txToAdd = numTxToAdd - pendingTxIds.size();
        if (!txs.isEmpty()) {
            txs.stream()
                    .map(txIdAndHash -> txIdAndHash._1)
                    .filter(txHash -> !pendingTxIds.contains(txHash))
                    .limit(txToAdd)
                    .forEach(pendingTxIds::add);
        } else {
            if (log.isDebugEnabled())
                log.debug("Nothing to do, txs is empty");
        }
    }

    private void addTxToQueue(String txHash) {
        if (!pendingTxIds.contains(txHash)) {
            pendingTxIds.add(txHash);
        }
    }

    private void removeAcknowledgedTxs(int numAcknowledgedTransactions) {
        if (numAcknowledgedTransactions > 0) {
            var numTxToRemove = Math.min(numAcknowledgedTransactions, pendingTxIds.size());
            var ackedTxIds = new ArrayList<>(pendingTxIds.subList(0, numTxToRemove));
            if (log.isDebugEnabled())
                log.debug("removeAcknowledgedTxs: {}, ackedTxIds: {}", numAcknowledgedTransactions, ackedTxIds);
            ackedTxIds.forEach(txHash -> {
                // remove from map
                removeTxIdAndHash(txHash);
                // removed from queue
                pendingTxIds.remove(txHash);
            });
        }

    }

    public void enqueueTransaction(String txHash, byte[] txBytes) {
        if (txs.stream().map(txIdAndHash -> txIdAndHash._1).anyMatch(previousHash -> previousHash.equals(txHash))) {
            return;
        }
        txs.add(new Tuple<>(txHash, txBytes));
        if (TxSubmissionState.TxIdsBlocking.equals(currenState)) {
            addTxToQueue(txHash);
            this.sendNextMessage();
        }
    }

    public boolean hasPendingTx() {
        return !pendingTxIds.isEmpty();
    }

    @Override
    public boolean isDone() {
        return this.currenState == TxSubmissionState.Done;
    }

    @Override
    public void reset() {
        txs.clear();
        pendingTxIds.clear();
        requestedTxIds.clear();
        this.currenState = TxSubmissionState.Init;
    }
}
