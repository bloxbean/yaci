package com.bloxbean.cardano.yaci.core.protocol.txsubmission;

import com.bloxbean.cardano.yaci.core.common.TxBodyType;
import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges.*;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.model.TxSubmissionRequest;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;

@Slf4j
public class TxSubmissionAgent extends Agent<TxSubmissionListener> {
    private static final int DEFAULT_MAX_QUEUE_SIZE = 1000;

    // txs should be stored in a thread-safe, ordered (tx dependency/chaining) data structure.
    private final ConcurrentLinkedQueue<TxSubmissionRequest> txs;
    /**
     * Is the queue of TX received from client
     */
    private final ConcurrentLinkedQueue<TxId> pendingTxIds;
    /**
     * It's the temporary list of TX ids requested from Server
     */
    private final ConcurrentLinkedQueue<TxId> requestedTxIds;

    private final int maxQueueSize;

    public TxSubmissionAgent() {
        this(true);
    }

    public TxSubmissionAgent(boolean isClient) {
        this(isClient, DEFAULT_MAX_QUEUE_SIZE);
    }

    public TxSubmissionAgent(boolean isClient, int maxQueueSize) {
        super(isClient);
        this.currentState = TxSubmissionState.Init;
        this.txs = new ConcurrentLinkedQueue<>();
        this.pendingTxIds = new ConcurrentLinkedQueue<>();
        this.requestedTxIds = new ConcurrentLinkedQueue<>();
        this.maxQueueSize = maxQueueSize;
    }

    @Override
    public int getProtocolId() {
        return 4;
    }

    @Override
    public Message buildNextMessage() {
        switch ((TxSubmissionState) currentState) {
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

    private Optional<TxSubmissionRequest> findTxIdAndHash(TxId txId) {
        return txs.stream().filter(txSubmissionRequest -> txSubmissionRequest.getTxHash().equals(HexUtil.encodeHexString(txId.getTxId()))).findAny();
    }

    private Optional<TxSubmissionRequest> removeTxIdAndHash(TxId txId) {
        var txIdAndHashOpt = txs.stream().filter(txSubmissionRequest -> txSubmissionRequest.getTxHash().equals(HexUtil.encodeHexString(txId.getTxId()))).findAny();
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
                    .forEach(txSubmissionRequest ->
                            replyTxIds.addTxId(txSubmissionRequest.getTxBodyType().getEra(), txSubmissionRequest.getTxHash(), txSubmissionRequest.getTxnBytes().length)
                    );
            log.info("Sending {} TxId(s) to upstream node", replyTxIds.getTxIdAndSizeMap().size());
            return replyTxIds;
        }
        return new ReplyTxIds();
    }

    private ReplyTxs getReplyTxs() {
        if (requestedTxIds.isEmpty())
            return new ReplyTxs();

        ReplyTxs replyTxs = new ReplyTxs();
        requestedTxIds.forEach(txId -> findTxIdAndHash(txId).ifPresent(txSubmissionRequest ->
                replyTxs.addTx(new Tx(txSubmissionRequest.getTxBodyType().getEra(), txSubmissionRequest.getTxnBytes()))
        ));
        // Proactively clean delivered txs from txs queue — byte[] bodies have been serialized
        requestedTxIds.forEach(this::removeTxIdAndHash);
        requestedTxIds.clear();

        log.info("Sending {} Tx(s) to upstream node", replyTxs.getTxns().size());
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
                    .map(tx -> new TxId(tx.getTxBodyType().getEra(), HexUtil.decodeHexString(tx.getTxHash())))
                    .filter(txId -> !pendingTxIds.contains(txId))
                    .limit(txToAdd)
                    .forEach(pendingTxIds::add);
        } else {
            if (log.isDebugEnabled())
                log.debug("Nothing to do, txs is empty");
        }
    }

    private void addTxToQueue(TxId txId) {
        if (!pendingTxIds.contains(txId)) {
            pendingTxIds.add(txId);
        }
    }

    private void removeAcknowledgedTxs(int numAcknowledgedTransactions) {
        if (numAcknowledgedTransactions > 0) {
            var numTxToRemove = Math.min(numAcknowledgedTransactions, pendingTxIds.size());
            for (int i = 0; i < numTxToRemove; i++) {
                var txHash = pendingTxIds.poll();
                if (txHash != null) {
                    removeTxIdAndHash(txHash);
                }
            }
            if (numAcknowledgedTransactions > numTxToRemove) {
                log.warn("Ack count {} exceeded pendingTxIds size {}", numAcknowledgedTransactions, numTxToRemove);
            }
            log.info("Upstream node acknowledged {} tx(s), remaining in queue: {}", numTxToRemove, txs.size());
        }
    }

    /**
     * Enqueue a transaction for submission to the upstream node.
     *
     * @return true if the transaction was enqueued, false if rejected (queue full or duplicate)
     */
    public boolean enqueueTransaction(String txHash, byte[] txBytes, TxBodyType txBodyType) {
        boolean shouldWake;
        synchronized (this) {
            if (txs.size() >= maxQueueSize) {
                log.warn("Tx queue full ({}/{}), rejecting: {}", txs.size(), maxQueueSize, txHash);
                return false;
            }
            if (txs.stream().anyMatch(req -> req.getTxHash().equals(txHash))) {
                return false; // duplicate
            }
            txs.add(TxSubmissionRequest.builder().txHash(txHash).txnBytes(txBytes).txBodyType(txBodyType).build());
            shouldWake = TxSubmissionState.TxIdsBlocking.equals(currentState);
            if (shouldWake) {
                addTxToQueue(new TxId(txBodyType.getEra(), HexUtil.decodeHexString(txHash)));
            }
        }
        log.info("Transaction enqueued: {}, total txs in queue: {}", txHash, txs.size());
        if (shouldWake) {
            Channel ch = getChannel();
            if (ch != null && ch.isActive()) {
                ch.eventLoop().execute(this::sendNextMessage);
            }
        }
        return true;
    }

    public boolean hasPendingTx() {
        return !pendingTxIds.isEmpty();
    }

    public int getQueueSize() {
        return txs.size();
    }

    @Override
    public boolean isDone() {
        return this.currentState == TxSubmissionState.Done;
    }

    @Override
    public void reset() {
        txs.clear();
        pendingTxIds.clear();
        requestedTxIds.clear();
        this.currentState = TxSubmissionState.Init;
    }
}
