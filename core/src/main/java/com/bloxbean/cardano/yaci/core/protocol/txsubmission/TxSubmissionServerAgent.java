package com.bloxbean.cardano.yaci.core.protocol.txsubmission;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges.*;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TxSubmission Server Agent - handles the server side of the TxSubmission protocol.
 *
 * This implementation uses a blocking-only approach:
 * - Responds to Init message from client
 * - Sends blocking RequestTxIds to get transactions
 * - Maintains FIFO queue of outstanding transaction IDs
 * - Properly acknowledges processed transactions
 * - Respects protocol constraint: (outstanding - ack) + req ≤ 10
 */
@Slf4j
public class TxSubmissionServerAgent extends Agent<TxSubmissionListener> {

    // Protocol constraint: max unacknowledged transactions
    private static final int MAX_UNACKNOWLEDGED = 10;
    private static final int DEFAULT_BATCH_SIZE = 10;  // Can safely use 10 since we process all before requesting more

    // FIFO queue for outstanding transaction IDs (waiting to be processed)
    private final Queue<TxId> outstandingTxIds = new ConcurrentLinkedQueue<>();

    // Count of transactions that need to be acknowledged
    private final AtomicInteger pendingAcknowledgments = new AtomicInteger(0);

    // Track all transaction IDs we've seen
    private final Set<String> seenTxIds = new HashSet<>();

    // Pending request to send when we have agency
    private volatile Message pendingRequest;

    // Configuration
    private final TxSubmissionConfig config;

    public TxSubmissionServerAgent() {
        this(TxSubmissionConfig.createDefault());
    }

    public TxSubmissionServerAgent(TxSubmissionConfig config) {
        super(false); // Server agent
        this.config = config;
        this.currentState = TxSubmissionState.Init;

        log.info("" +
                "", currentState);
    }

    @Override
    public int getProtocolId() {
        return 4;
    }

    @Override
    public Message buildNextMessage() {
        // Server agent is mostly reactive - it responds to client messages
        // Only send messages when we have agency and want to request something

        log.debug("TxSubmissionServerAgent.buildNextMessage() called - state: {}, hasAgency: {}, pendingRequest: {}",
                 currentState, hasAgency(), pendingRequest != null ? pendingRequest.getClass().getSimpleName() : "null");

        switch ((TxSubmissionState) currentState) {
            case Idle:
                // In Idle state, server has agency and could send RequestTxIds or RequestTxs
                if (pendingRequest != null) {
                    // For testing purposes (when no channel is set), always return the message
                    // In production (when channel is set), only return if channel is active
                    if (getChannel() == null) {
                        // Test mode - no channel set, allow message building
                        Message toSend = pendingRequest;
                        pendingRequest = null;
                        log.debug("TxSubmissionServerAgent sending {} (test mode)", toSend.getClass().getSimpleName());
                        return toSend;
                    } else if (getChannel().isActive()) {
                        // Production mode - channel exists and is active
                        Message toSend = pendingRequest;
                        pendingRequest = null;
                        log.info("TxSubmissionServerAgent sending {}", toSend.getClass().getSimpleName());
                        return toSend;
                    } else {
                        // Production mode - channel exists but not active, keep pendingRequest
                        log.debug("Channel not active, keeping pendingRequest for retry");
                        return null;
                    }
                }
                // IMPORTANT: If we have no pending request, we should not have agency
                // This prevents infinite loops where we repeatedly return null but still have agency
                log.trace("TxSubmissionServerAgent: No pending request in Idle state - returning null");
                return null;
            default:
                // In other states, client has agency so we don't send messages
                log.trace("TxSubmissionServerAgent: Client has agency in state {}", currentState);
                return null;
        }
    }

    @Override
    public void processResponse(Message message) {
        if (message == null) return;

        log.debug("Processing message: {} in state: {}", message.getClass().getSimpleName(), currentState);

        if (message instanceof Init) {
            handleInit();
        } else if (message instanceof ReplyTxIds) {
            handleReplyTxIds((ReplyTxIds) message);
        } else if (message instanceof ReplyTxs) {
            handleReplyTxs((ReplyTxs) message);
        } else {
            log.warn("Unexpected message type: {}", message.getClass().getSimpleName());
        }
    }

    private void handleInit() {
        log.info("Received Init message from client - transitioning to Idle state");

        // Send initial blocking request for transactions
        sendInitialBlockingRequest();
    }

    private void handleReplyTxIds(ReplyTxIds replyTxIds) {
        log.info("Received ReplyTxIds with {} transaction IDs", replyTxIds.getTxIdAndSizeMap().size());

        if (replyTxIds.getTxIdAndSizeMap().isEmpty()) {
            log.info("No transactions available, sending next blocking request");
            sendNextBlockingRequest();
            return;
        }

        // Add transaction IDs to FIFO queue
        replyTxIds.getTxIdAndSizeMap().forEach((txId, size) -> {
            String txIdStr = txId.toString();
            if (!seenTxIds.contains(txIdStr)) {
                outstandingTxIds.offer(txId);
                seenTxIds.add(txIdStr);
                log.debug("Added tx ID to queue: {} (size: {} bytes)", txId, size);
            }
        });

        // Notify listeners
        getAgentListeners().forEach(listener -> listener.handleReplyTxIds(replyTxIds));

        // Request the actual transaction bodies
        if (!outstandingTxIds.isEmpty()) {
            requestTransactionBodies();
        }
    }

    private void handleReplyTxs(ReplyTxs replyTxs) {
        log.info("Received ReplyTxs with {} transactions", replyTxs.getTxns().size());

        // Process each transaction
        for (Tx tx : replyTxs.getTxns()) {
            // Remove from FIFO queue
            TxId processedTxId = outstandingTxIds.poll();
            if (processedTxId != null) {
                pendingAcknowledgments.incrementAndGet();
                log.debug("Processed transaction from queue, pending acks: {}", pendingAcknowledgments.get());
            }
        }

        // Notify listeners to process transactions
        getAgentListeners().forEach(listener -> listener.handleReplyTxs(replyTxs));

        // Check if we need to request more transactions
        if (outstandingTxIds.isEmpty()) {
            log.info("All transactions processed, sending next blocking request");
            sendNextBlockingRequest();
        }
    }

    private void sendInitialBlockingRequest() {
        short ackCount = 0; // No acknowledgments on initial request
        short reqCount = DEFAULT_BATCH_SIZE;

        log.info("Sending initial blocking request: ack={}, req={}", ackCount, reqCount);
        requestTxIds(ackCount, reqCount, true);
    }

    private void sendNextBlockingRequest() {
        // Get current acknowledgment count
        short ackCount = (short) Math.min(pendingAcknowledgments.getAndSet(0), Short.MAX_VALUE);

        // Calculate safe request count to respect protocol constraint
        // (outstanding - ack) + req ≤ 10
        // Since we're acknowledging all outstanding, we can request up to batch size
        short reqCount = (short) Math.min(DEFAULT_BATCH_SIZE, MAX_UNACKNOWLEDGED);

        log.info("Sending next blocking request: ack={}, req={}", ackCount, reqCount);
        requestTxIds(ackCount, reqCount, true);
    }

    /**
     * Request transaction IDs from the client (blocking mode only)
     *
     * @param ackTxIds Number of transactions to acknowledge
     * @param reqTxIds Number of transaction IDs to request
     * @param blocking Must be true for this implementation
     */
    private void requestTxIds(short ackTxIds, short reqTxIds, boolean blocking) {
        if (currentState != TxSubmissionState.Idle) {
            log.warn("Cannot request tx IDs in state: {}", currentState);
            return;
        }

        if (!blocking) {
            log.warn("Non-blocking mode not supported in this implementation, using blocking mode");
            blocking = true;
        }

        RequestTxIds requestTxIds = new RequestTxIds(blocking, ackTxIds, reqTxIds);
        log.info("Requesting {} tx IDs (ack: {}, blocking: {})", reqTxIds, ackTxIds, blocking);

        // Queue the request for sending
        this.pendingRequest = requestTxIds;

        // Send immediately if we have agency
        if (hasAgency()) {
            if (getChannel() != null && getChannel().isActive()) {
                log.debug("Sending RequestTxIds immediately");
                sendNextMessage();
            } else if (getChannel() == null) {
                // Test mode - no channel
                log.debug("No channel set (test mode)");
            }
        }
    }

    /**
     * Request specific transactions from the client
     *
     * @param txIds List of transaction IDs to request
     */
    private void requestTxs(List<TxId> txIds) {
        if (currentState != TxSubmissionState.Idle) {
            log.warn("Cannot request txs in state: {}", currentState);
            return;
        }

        RequestTxs requestTxs = new RequestTxs();
        txIds.forEach(requestTxs::addTxnId);
        log.info("Requesting {} transaction bodies", txIds.size());

        // Queue the request for sending
        this.pendingRequest = requestTxs;

        // Send immediately if we have agency
        if (hasAgency() && getChannel() != null && getChannel().isActive()) {
            log.debug("Sending RequestTxs immediately");
            sendNextMessage();
        } else if (getChannel() == null) {
            // Test mode
            log.debug("No channel set (test mode)");
        }
    }

    @Override
    public boolean isDone() {
        return this.currentState == TxSubmissionState.Done;
    }

    @Override
    public void reset() {
        outstandingTxIds.clear();
        seenTxIds.clear();
        pendingAcknowledgments.set(0);
        pendingRequest = null;
        this.currentState = TxSubmissionState.Init;
        log.debug("TxSubmissionServerAgent reset");
    }

    /**
     * Get the number of transaction IDs we've seen
     */
    public int getReceivedTxIdCount() {
        return seenTxIds.size();
    }

    /**
     * Check if we've seen a specific transaction ID
     */
    public boolean hasReceivedTxId(String txId) {
        return seenTxIds.contains(txId);
    }

    /**
     * Get the number of outstanding transactions in the queue
     */
    public int getOutstandingTxCount() {
        return outstandingTxIds.size();
    }

    /**
     * Get the number of pending acknowledgments
     */
    public int getPendingAcknowledgments() {
        return pendingAcknowledgments.get();
    }

    /**
     * Request transaction bodies for all outstanding transaction IDs
     */
    private void requestTransactionBodies() {
        // Request all outstanding transaction IDs
        List<TxId> txIdsToRequest = new ArrayList<>(outstandingTxIds);

        if (!txIdsToRequest.isEmpty()) {
            log.info("Requesting {} transaction bodies", txIdsToRequest.size());
            requestTxs(txIdsToRequest);
        }
    }


    /**
     * Get the current configuration
     */
    public TxSubmissionConfig getConfig() {
        return config;
    }

    /**
     * Shutdown the agent and clean up resources
     */
    public void shutdown() {
        log.info("Shutting down TxSubmissionServerAgent");
        reset();
    }
}
