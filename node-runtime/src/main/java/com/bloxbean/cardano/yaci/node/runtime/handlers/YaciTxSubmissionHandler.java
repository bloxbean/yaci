package com.bloxbean.cardano.yaci.node.runtime.handlers;

import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.TxSubmissionListener;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges.*;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.events.api.EventMetadata;
import com.bloxbean.cardano.yaci.events.api.PublishOptions;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.node.api.events.TransactionValidateEvent;
import com.bloxbean.cardano.yaci.node.runtime.chain.MemPool;
import com.bloxbean.cardano.yaci.node.runtime.chain.MemPoolTransaction;
import com.bloxbean.cardano.yaci.node.runtime.events.MemPoolTransactionReceivedEvent;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementation of TxSubmissionListener and TxSubmissionHandler that integrates
 * TxSubmission protocol with YaciNode's MemPool and transaction processing.
 * 
 * This implementation uses a blocking-only approach where transactions are
 * processed immediately and removed from the mempool to simulate processing.
 */
@Slf4j
public class YaciTxSubmissionHandler implements TxSubmissionListener, TxSubmissionHandler {

    private final MemPool memPool;
    private final EventBus eventBus;
    private final boolean blockProducerMode;
    private final Set<String> knownTxIds = ConcurrentHashMap.newKeySet();
    private final Map<String, String> clientConnections = new ConcurrentHashMap<>();

    // Statistics
    private long txIdsReceived = 0;
    private long txsReceived = 0;
    private long txsAccepted = 0;
    private long txsRejected = 0;
    private long txsProcessed = 0;

    public YaciTxSubmissionHandler(MemPool memPool, EventBus eventBus) {
        this(memPool, eventBus, false);
    }

    public YaciTxSubmissionHandler(MemPool memPool, EventBus eventBus, boolean blockProducerMode) {
        this.memPool = memPool;
        this.eventBus = eventBus;
        this.blockProducerMode = blockProducerMode;
    }

    // TxSubmissionListener implementation (for server-side handling)

    @Override
    public void handleRequestTxs(RequestTxs requestTxs) {
        // This is called when acting as a client - not used in server mode
        log.debug("Received RequestTxs message (not applicable in server mode): {} tx IDs",
                requestTxs.getTxIds().size());
    }

    @Override
    public void handleRequestTxIdsNonBlocking(RequestTxIds requestTxIds) {
        // This is called when acting as a client - not used in server mode
        log.debug("Received RequestTxIds non-blocking message (not applicable in server mode): ack={}, req={}",
                requestTxIds.getAckTxIds(), requestTxIds.getReqTxIds());
    }

    @Override
    public void handleRequestTxIdsBlocking(RequestTxIds requestTxIds) {
        // This is called when acting as a client - not used in server mode
        log.debug("Received RequestTxIds blocking message (not applicable in server mode): ack={}, req={}",
                requestTxIds.getAckTxIds(), requestTxIds.getReqTxIds());
    }

    @Override
    public void handleReplyTxIds(ReplyTxIds replyTxIds) {
        txIdsReceived += replyTxIds.getTxIdAndSizeMap().size();

        log.info("Received {} transaction IDs from client",
                replyTxIds.getTxIdAndSizeMap().size());

        // Track the transaction IDs we've seen
        replyTxIds.getTxIdAndSizeMap().forEach((txId, size) -> {
            String txIdStr = HexUtil.encodeHexString(txId.getTxId());
            knownTxIds.add(txIdStr);
            if (log.isDebugEnabled()) {
                log.debug("TX ID: {} (size: {} bytes)", txIdStr, size);
            }
        });
    }

    @Override
    public void handleReplyTxs(ReplyTxs replyTxs) {
        txsReceived += replyTxs.getTxns().size();

        log.info("Received {} transactions from client", replyTxs.getTxns().size());

        for (Tx tx : replyTxs.getTxns()) {
            try {
                String txHash = TransactionUtil.getTxHash(tx.getTx());

                // Validate via event bus — registered listeners (default + plugins) will veto if invalid
                var validateEvent = new TransactionValidateEvent(tx.getTx(), txHash, "txsubmission");
                if (eventBus != null) {
                    eventBus.publish(validateEvent,
                            EventMetadata.builder().origin("txsubmission").build(),
                            PublishOptions.builder().build());
                }

                if (validateEvent.isRejected()) {
                    txsRejected++;
                    String errorMsg = validateEvent.rejections().stream()
                            .map(r -> r.reason())
                            .collect(java.util.stream.Collectors.joining("; "));
                    log.warn("Rejecting invalid N2N tx {}: {}", txHash, errorMsg);
                    continue;
                }

                // Add to mempool and publish event
                MemPoolTransaction mpt = memPool.addTransaction(tx.getTx());
                if (eventBus != null && mpt != null) {
                    eventBus.publish(new MemPoolTransactionReceivedEvent(mpt),
                            EventMetadata.builder().origin("txsubmission").build(),
                            PublishOptions.builder().build());
                }
                txsAccepted++;

                log.info("Transaction added to mempool: {} ({} bytes)", txHash, tx.getTx().length);

            } catch (Exception e) {
                txsRejected++;
                log.warn("Failed to process received transaction: {}", e.getMessage());
                if (log.isDebugEnabled()) {
                    log.debug("Transaction processing error details", e);
                }
            }
        }
    }

    // TxSubmissionHandler implementation

    @Override
    public void handleTransaction(TxId txId, byte[] txBytes) {
        // This method is not used in the blocking-only approach
        // Transactions are handled directly in handleReplyTxs
        log.debug("handleTransaction called (not used in blocking mode)");
    }

    @Override
    public void handleTransactionIds(Map<TxId, Integer> txIdAndSizes) {
        // Not used in blocking-only approach
        log.debug("handleTransactionIds called (not used in blocking mode)");
    }

    @Override
    public boolean shouldRequestTransaction(TxId txId) {
        // In blocking mode, we request all transactions announced
        return true;
    }

    @Override
    public void onClientConnected(String clientId) {
        clientConnections.put(clientId, clientId);
        log.info("TxSubmission client connected: {}", clientId);
        log.debug("Client connected - will request transactions once handshake completes");
    }

    @Override
    public void onClientDisconnected(String clientId) {
        clientConnections.remove(clientId);
        log.info("TxSubmission client disconnected: {}", clientId);
    }

    @Override
    public short getAcknowledgeCount() {
        // Not used in the new blocking-only implementation
        // Acknowledgments are handled directly in the agent
        return 0;
    }

    @Override
    public short getRequestCount() {
        // Not used in the new blocking-only implementation
        return 5;
    }

    @Override
    public boolean useBlockingMode() {
        // Always use blocking mode in this implementation
        return true;
    }

    // Note: Agent now manages its own periodic requests automatically


    // Statistics and monitoring

    public long getTxIdsReceived() {
        return txIdsReceived;
    }

    public long getTxsReceived() {
        return txsReceived;
    }

    public long getTxsAccepted() {
        return txsAccepted;
    }

    public long getTxsRejected() {
        return txsRejected;
    }

    public long getTxsProcessed() {
        return txsProcessed;
    }

    public int getConnectedClients() {
        return clientConnections.size();
    }

    public int getKnownTxIds() {
        return knownTxIds.size();
    }

    public int getMempoolSize() {
        return memPool.size();
    }

    /**
     * Get summary statistics for monitoring
     */
    public String getStatsMessage() {
        return String.format("TxSubmission Stats - Clients: %d, TxIDs: %d, TxReceived: %d, TxAccepted: %d, TxProcessed: %d, TxRejected: %d, MemPool: %d",
                getConnectedClients(), getTxIdsReceived(), getTxsReceived(), getTxsAccepted(), getTxsProcessed(), getTxsRejected(), getMempoolSize());
    }

    /**
     * Reset statistics (useful for testing)
     */
    public void resetStats() {
        txIdsReceived = 0;
        txsReceived = 0;
        txsAccepted = 0;
        txsRejected = 0;
        txsProcessed = 0;
        knownTxIds.clear();
        clientConnections.clear();
    }
}
