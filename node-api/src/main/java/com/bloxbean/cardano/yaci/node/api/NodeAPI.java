package com.bloxbean.cardano.yaci.node.api;

import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import com.bloxbean.cardano.yaci.node.api.config.NodeConfig;
import com.bloxbean.cardano.yaci.node.api.listener.NodeEventListener;
import com.bloxbean.cardano.yaci.node.api.model.FundResult;
import com.bloxbean.cardano.yaci.node.api.model.GenesisParameters;
import com.bloxbean.cardano.yaci.node.api.model.NodeStatus;
import com.bloxbean.cardano.yaci.node.api.model.SnapshotInfo;
import com.bloxbean.cardano.yaci.node.api.model.TimeAdvanceResult;
import com.bloxbean.cardano.yaci.node.api.utxo.UtxoState;

import java.util.List;

/**
 * Main interface for Yaci Node operations.
 * Provides a framework-agnostic API for node lifecycle management,
 * status monitoring, and blockchain data access.
 */
public interface NodeAPI {

    /**
     * Start the node with its configured settings.
     * This will initialize client and/or server components based on configuration.
     *
     * @throws IllegalStateException if the node is already running
     * @throws RuntimeException if startup fails
     */
    void start();

    /**
     * Stop the node and cleanup all resources.
     * This will gracefully shutdown client and server components.
     */
    void stop();

    /**
     * Check if the node is currently running.
     *
     * @return true if the node is running, false otherwise
     */
    boolean isRunning();

    /**
     * Check if the node is currently syncing with remote peers.
     *
     * @return true if actively syncing, false otherwise
     */
    boolean isSyncing();

    /**
     * Check if the server component is running and accepting connections.
     *
     * @return true if server is running, false otherwise
     */
    boolean isServerRunning();

    /**
     * Get the current status of the node including sync progress and statistics.
     *
     * @return current node status
     */
    NodeStatus getStatus();

    /**
     * Get the current tip of the local chain.
     *
     * @return the local chain tip, or null if no blocks have been processed
     */
    ChainTip getLocalTip();

    /**
     * Add a listener for blockchain data events.
     * The listener will receive callbacks for blocks, rollbacks, and other chain events.
     *
     * @param listener the blockchain data listener to add
     */
    void addBlockChainDataListener(BlockChainDataListener listener);

    /**
     * Remove a previously added blockchain data listener.
     *
     * @param listener the blockchain data listener to remove
     */
    void removeBlockChainDataListener(BlockChainDataListener listener);

    /**
     * Add a listener for node-level events (startup, shutdown, status changes).
     *
     * @param listener the node event listener to add
     */
    void addNodeEventListener(NodeEventListener listener);

    /**
     * Remove a previously added node event listener.
     *
     * @param listener the node event listener to remove
     */
    void removeNodeEventListener(NodeEventListener listener);

    /**
     * Get access to the underlying ChainState for advanced operations.
     * This provides direct access to block storage and chain queries.
     *
     * @return the chain state instance
     */
    ChainState getChainState();

    /**
     * Get the configuration used by this node.
     *
     * @return the node configuration
     */
    NodeConfig getConfig();

    /**
     * Recover chain state from corruption by finding the last valid continuous point
     * and removing all corrupted data after that point.
     *
     * This method should only be called when the node is not running.
     *
     * @return true if recovery was performed, false if no corruption was detected
     * @throws IllegalStateException if the node is currently running
     * @throws RuntimeException if recovery fails
     */
    boolean recoverChainState();

    /**
     * Submit a transaction to the node's mempool.
     * In block producer mode, the transaction will be included in a future block.
     *
     * @param txCbor the complete transaction CBOR bytes
     * @return the transaction hash as a hex string
     */
    String submitTransaction(byte[] txCbor);

    /**
     * Register multiple event listeners at once.
     * Each listener object will be scanned for annotated event handler methods.
     *
     * @param listeners one or more listener objects to register
     */
    void registerListeners(Object... listeners);

    /**
     * Register an event listener with specific subscription options.
     * @param listener
     * @param sbOptions
     */
    void registerListener(Object listener, SubscriptionOptions sbOptions);

    /**
     * Access the UTXO state if enabled.
     * Returns null if UTXO is disabled or not initialized.
     */
    UtxoState getUtxoState();

    /**
     * Get the protocol parameters JSON string.
     * Only available when block producer mode is enabled and a protocol parameters file is configured.
     *
     * @return protocol parameters as a JSON string, or null if not available
     */
    String getProtocolParameters();

    /**
     * Get genesis parameters from shelley-genesis.json.
     * Returns null if genesis data is not available.
     *
     * @return genesis parameters, or null
     */
    GenesisParameters getGenesisParameters();

    /**
     * Get the current epoch nonce state as a map (for verification/debugging).
     * Returns null if nonce tracking is not active.
     *
     * @return map with keys: epoch, nonce, evolving_nonce, candidate_nonce; or null
     */
    default java.util.Map<String, Object> getEpochNonceInfo() {
        return null;
    }

    /**
     * Trigger a controlled rollback. Requires dev mode.
     * Rolls back chain state to the given slot, publishes RollbackEvent,
     * and notifies connected clients via n2n protocol.
     *
     * @param targetSlot the slot to roll back to
     * @throws IllegalStateException if dev mode is not enabled
     * @throws IllegalArgumentException if target slot is invalid
     */
    void rollbackTo(long targetSlot);

    // --- Devnet developer tools ---

    /**
     * Create a named snapshot of the current chain state.
     * Requires dev mode with RocksDB storage.
     *
     * @param name the snapshot name
     * @return snapshot metadata
     * @throws IllegalStateException if dev mode is not enabled or storage is not RocksDB
     */
    SnapshotInfo createSnapshot(String name);

    /**
     * Restore chain state from a previously created snapshot.
     * Requires dev mode. Stops block production, restores state, clears mempool, and resumes.
     * Connected clients will need to reconnect.
     *
     * @param name the snapshot name to restore
     * @throws IllegalStateException if dev mode is not enabled
     * @throws IllegalArgumentException if snapshot does not exist
     */
    void restoreSnapshot(String name);

    /**
     * List all available snapshots.
     *
     * @return list of snapshot metadata, ordered by creation time
     */
    List<SnapshotInfo> listSnapshots();

    /**
     * Delete a named snapshot.
     *
     * @param name the snapshot name to delete
     * @throws IllegalArgumentException if snapshot does not exist
     */
    void deleteSnapshot(String name);

    /**
     * Inject a synthetic UTXO to fund an address (faucet).
     * The UTXO is stored directly without a real transaction.
     * Requires dev mode.
     *
     * @param address bech32 address to fund
     * @param lovelace amount in lovelace
     * @return the synthetic UTXO reference
     * @throws IllegalStateException if dev mode is not enabled or UTXO store is unavailable
     */
    FundResult fundAddress(String address, long lovelace);

    /**
     * Advance time by producing empty blocks rapidly.
     * Requires dev mode.
     *
     * @param slots number of slots to advance
     * @return result with new tip information
     * @throws IllegalStateException if dev mode is not enabled
     * @throws IllegalArgumentException if slots is invalid
     */
    TimeAdvanceResult advanceTimeBySlots(int slots);

    /**
     * Advance time by a duration, producing empty blocks rapidly.
     * The number of slots advanced = seconds * 1000 / slotLengthMillis.
     * Requires dev mode.
     *
     * @param seconds number of seconds to advance
     * @return result with new tip information
     * @throws IllegalStateException if dev mode is not enabled
     * @throws IllegalArgumentException if seconds is invalid
     */
    TimeAdvanceResult advanceTimeBySeconds(int seconds);

    /**
     * Convert a slot number to a Unix timestamp (seconds since epoch).
     * Uses era-aware calculation based on genesis parameters and era start slots.
     *
     * @param slot the slot number
     * @return Unix timestamp in seconds, or 0 if timing data is not available
     */
    long slotToUnixTime(long slot);

    /**
     * Shift genesis timestamp back by a number of epochs and start block producer.
     * Used in past-time-travel mode where block production is deferred.
     * Requires past-time-travel-mode=true and dev mode.
     *
     * @param epochs number of epochs to shift genesis back
     * @return the shift in milliseconds
     * @throws IllegalStateException if not in past-time-travel mode or producer already started
     */
    default long shiftGenesisAndStartProducer(int epochs) {
        throw new UnsupportedOperationException("shiftGenesisAndStartProducer not supported by this implementation");
    }

    /**
     * Catch up to wall-clock slot by rapidly producing blocks.
     * Used in past-time-travel mode after epoch shifts and tx injection are done.
     * Requires dev mode.
     *
     * @return the time advance result
     * @throws IllegalStateException if dev mode is not enabled or producer not running
     */
    default TimeAdvanceResult catchUpToWallClock() {
        throw new UnsupportedOperationException("catchUpToWallClock not supported by this implementation");
    }
}
