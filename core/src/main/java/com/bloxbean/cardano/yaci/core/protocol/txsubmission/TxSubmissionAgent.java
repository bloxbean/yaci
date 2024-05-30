package com.bloxbean.cardano.yaci.core.protocol.txsubmission;

import com.bloxbean.cardano.yaci.core.common.TxBodyType;
import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges.*;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.model.TxSubmissionRequest;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;

@Slf4j
public class TxSubmissionAgent extends Agent<TxSubmissionListener> {
    // txs should be stored in a thread-safe, ordered (tx dependency/chaining) data structure.
    private final ConcurrentLinkedQueue<TxSubmissionRequest> txs;
    /**
     * Is the queue of TX received from client
     */
    private final ConcurrentLinkedQueue<String> pendingTxIds;
    /**
     * It's the temporary list of TX ids requested from Server
     */
    private final ConcurrentLinkedQueue<String> requestedTxIds;

    public TxSubmissionAgent() {
        this(true);
    }
    public TxSubmissionAgent(boolean isClient) {
        super(isClient);
        this.currenState = TxSubmissionState.Init;
        this.txs = new ConcurrentLinkedQueue<>();
        this.pendingTxIds = new ConcurrentLinkedQueue<>();
        this.requestedTxIds = new ConcurrentLinkedQueue<>();
    }

    @Override
    public int getProtocolId() {
        return 4;
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

    private Optional<TxSubmissionRequest> findTxIdAndHash(String id) {
        return txs.stream().filter(txSubmissionRequest -> txSubmissionRequest.getTxHash().equals(id)).findAny();
    }

    private Optional<TxSubmissionRequest> removeTxIdAndHash(String id) {
        var txIdAndHashOpt = txs.stream().filter(txSubmissionRequest -> txSubmissionRequest.getTxHash().equals(id)).findAny();
        txIdAndHashOpt.ifPresent(txs::remove);
        return txIdAndHashOpt;
    }

    private ReplyTxIds getReplyTxIds() {
        if (!pendingTxIds.isEmpty()) {
            ReplyTxIds replyTxIds = new ReplyTxIds();
            // Not limiting how many txs to add, as pendingTxIds should be already capped to num of req txs
            pendingTxIds
                    .stream()
                    .flatMap(id -> findTxIdAndHash(id).stream())
                    .forEach(txSubmissionRequest -> replyTxIds.addTxId(txSubmissionRequest.getTxHash(), txSubmissionRequest.getTxnBytes().length));
            if (log.isDebugEnabled())
                log.debug("TxIds: {}", replyTxIds.getTxIdAndSizeMap().size());
            return replyTxIds;
        }
        return new ReplyTxIds();
    }

    private ReplyTxs getReplyTxs() {
        if (requestedTxIds.isEmpty())
            return new ReplyTxs();

        ReplyTxs replyTxs = new ReplyTxs();
        requestedTxIds.forEach(txId -> findTxIdAndHash(txId).ifPresent(txSubmissionRequest -> replyTxs.addTx(txSubmissionRequest.getTxnBytes())));

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
                    .map(TxSubmissionRequest::getTxHash)
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
            for (int i = 0; i < numTxToRemove; i++) {
                var txHash = pendingTxIds.poll();
                if (txHash != null) {
                    // remove from map
                    removeTxIdAndHash(txHash);
                }
            }

        }

    }

    public void enqueueTransaction(String txHash, byte[] txBytes, TxBodyType txBodyType) {
        if (txs.stream().anyMatch(txSubmissionRequest -> txSubmissionRequest.getTxHash().equals(txHash))) {
            return;
        }
        txs.add(TxSubmissionRequest.builder().txHash(txHash).txnBytes(txBytes).txBodyType(txBodyType).build());
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
