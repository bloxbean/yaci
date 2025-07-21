package com.bloxbean.cardano.yaci.core.protocol.txsubmission;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges.*;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TxSubmission Server Agent - handles the server side of the TxSubmission protocol.
 * 
 * The server agent is much simpler than the client agent:
 * - Responds to Init message from client
 * - Can optionally request transactions by sending RequestTxIds
 * - Processes ReplyTxIds from client  
 * - Can request specific transactions by sending RequestTxs
 * - Processes ReplyTxs from client
 * 
 * For basic functionality, this agent is mostly reactive and doesn't proactively
 * request transactions from clients.
 */
@Slf4j
public class TxSubmissionServerAgent extends Agent<TxSubmissionListener> {
    
    // Track transactions we've received from clients
    private final Set<String> receivedTxIds = ConcurrentHashMap.newKeySet();
    
    // Pending request to send when we have agency
    private Message pendingRequest;
    
    public TxSubmissionServerAgent() {
        super(false); // Server agent
        this.currenState = TxSubmissionState.Init;
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
                 currenState, hasAgency(), pendingRequest != null ? pendingRequest.getClass().getSimpleName() : "null");
        
        switch ((TxSubmissionState) currenState) {
            case Idle:
                // In Idle state, server has agency and could send RequestTxIds or RequestTxs
                if (pendingRequest != null) {
                    Message toSend = pendingRequest;
                    pendingRequest = null;
                    log.debug("TxSubmissionServerAgent returning pendingRequest: {}", toSend.getClass().getSimpleName());
                    return toSend;
                }
                // IMPORTANT: If we have no pending request, we should not have agency
                // This prevents infinite loops where we repeatedly return null but still have agency
                log.debug("TxSubmissionServerAgent: No pending request in Idle state - returning null (will not send more messages)");
                return null;
            default:
                // In other states, client has agency so we don't send messages
                log.debug("TxSubmissionServerAgent: Client has agency in state {}", currenState);
                return null;
        }
    }

    @Override
    public void processResponse(Message message) {
        if (message == null) return;
        
        log.debug("Processing message: {} in state: {}", message.getClass().getSimpleName(), currenState);

        if (message instanceof Init) {
            log.debug("Received Init message from client");
            // Client has sent Init, we can transition to Idle where we have agency
            // No response needed - state transition happens automatically
            
        } else if (message instanceof ReplyTxIds) {
            ReplyTxIds replyTxIds = (ReplyTxIds) message;
            log.debug("Received ReplyTxIds with {} transactions", replyTxIds.getTxIdAndSizeMap().size());
            
            // Process the transaction IDs sent by client
            replyTxIds.getTxIdAndSizeMap().forEach((txId, size) -> {
                receivedTxIds.add(txId);
                log.debug("Received tx ID: {} (size: {})", txId, size);
            });
            
            // Notify listeners
            getAgentListeners().forEach(listener -> listener.handleReplyTxIds(replyTxIds));
            
        } else if (message instanceof ReplyTxs) {
            ReplyTxs replyTxs = (ReplyTxs) message;
            log.debug("Received ReplyTxs with {} transactions", replyTxs.getTxns().size());
            
            // Process the actual transactions sent by client
            replyTxs.getTxns().forEach(tx -> {
                log.debug("Received transaction: {} bytes", tx.length);
            });
            
            // Notify listeners
            getAgentListeners().forEach(listener -> listener.handleReplyTxs(replyTxs));
            
        } else {
            log.warn("Unexpected message type: {}", message.getClass().getSimpleName());
        }
    }

    /**
     * Request transaction IDs from the client (when server has agency in Idle state)
     * 
     * @param ackTxIds Number of transactions to acknowledge
     * @param reqTxIds Number of transaction IDs to request
     * @param blocking Whether to use blocking or non-blocking request
     */
    public void requestTxIds(short ackTxIds, short reqTxIds, boolean blocking) {
        if (currenState != TxSubmissionState.Idle) {
            log.warn("Cannot request tx IDs in state: {}", currenState);
            return;
        }
        
        RequestTxIds requestTxIds = new RequestTxIds(blocking, ackTxIds, reqTxIds);
        log.debug("Requesting {} tx IDs (ack: {}, blocking: {})", reqTxIds, ackTxIds, blocking);
        
        // Queue the request for sending when buildNextMessage is called
        this.pendingRequest = requestTxIds;
    }
    
    /**
     * Request specific transactions from the client (when server has agency in Idle state)
     * 
     * @param txIds List of transaction IDs to request
     */
    public void requestTxs(java.util.List<String> txIds) {
        if (currenState != TxSubmissionState.Idle) {
            log.warn("Cannot request txs in state: {}", currenState);
            return;
        }
        
        RequestTxs requestTxs = new RequestTxs();
        txIds.forEach(requestTxs::addTxnId);
        log.debug("Requesting {} transactions", txIds.size());
        
        // Queue the request for sending when buildNextMessage is called
        this.pendingRequest = requestTxs;
    }

    @Override
    public boolean isDone() {
        return this.currenState == TxSubmissionState.Done;
    }

    @Override
    public void reset() {
        receivedTxIds.clear();
        pendingRequest = null;
        this.currenState = TxSubmissionState.Init;
    }
    
    /**
     * Get the number of transaction IDs we've received from clients
     */
    public int getReceivedTxIdCount() {
        return receivedTxIds.size();
    }
    
    /**
     * Check if we've received a specific transaction ID
     */
    public boolean hasReceivedTxId(String txId) {
        return receivedTxIds.contains(txId);
    }
}