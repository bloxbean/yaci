package com.bloxbean.cardano.yaci.node.runtime.handlers;

import com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges.TxId;

import java.util.Map;

/**
 * Handler interface for processing transactions received via the TxSubmission protocol.
 * Implementations of this interface define how the node processes incoming transactions
 * from connected Cardano node clients.
 */
public interface TxSubmissionHandler {

    /**
     * Handle a transaction received from a connected client.
     * This method is called when a complete transaction body is received via ReplyTxs message.
     *
     * @param txId The transaction ID (hash)
     * @param txBytes The raw CBOR bytes of the transaction
     */
    void handleTransaction(TxId txId, byte[] txBytes);

    /**
     * Handle transaction IDs announced by a connected client.
     * This method is called when a client announces available transactions via ReplyTxIds message.
     * The handler can decide which transactions to request based on this information.
     *
     * @param txIdAndSizes Map of transaction IDs to their sizes in bytes
     */
    void handleTransactionIds(Map<TxId, Integer> txIdAndSizes);

    /**
     * Determine whether to request a specific transaction from the client.
     * This method is used to filter which transactions should be fetched.
     *
     * @param txId The transaction ID to check
     * @return true if the transaction should be requested, false otherwise
     */
    boolean shouldRequestTransaction(TxId txId);

    /**
     * Called when a client connects and is ready to submit transactions.
     * Can be used to initialize per-client state or request initial transactions.
     *
     * @param clientId Identifier for the connected client
     */
    default void onClientConnected(String clientId) {
        // Default empty implementation
    }

    /**
     * Called when a client disconnects.
     * Can be used to clean up per-client state.
     *
     * @param clientId Identifier for the disconnected client
     */
    default void onClientDisconnected(String clientId) {
        // Default empty implementation
    }

    /**
     * Get the number of transactions to acknowledge in the next RequestTxIds message.
     * This tells the client how many previously sent transactions we've processed.
     *
     * @return Number of transactions to acknowledge
     */
    default short getAcknowledgeCount() {
        return 0;
    }

    /**
     * Get the number of new transaction IDs to request from the client.
     * This controls how many transactions the client should announce.
     *
     * @return Number of transaction IDs to request
     */
    default short getRequestCount() {
        return 100; // Default batch size
    }

    /**
     * Determine whether to use blocking or non-blocking mode for RequestTxIds.
     * Blocking mode waits for transactions, non-blocking returns immediately.
     *
     * @return true for blocking mode, false for non-blocking
     */
    default boolean useBlockingMode() {
        return false; // Default to non-blocking
    }
}
