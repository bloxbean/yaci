package com.bloxbean.cardano.yaci.node.runtime.utxo;

import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.node.runtime.events.BlockAppliedEvent;
import com.bloxbean.cardano.yaci.node.runtime.events.RollbackEvent;

public interface UtxoStoreWriter {
    void applyBlock(BlockAppliedEvent e);
    void rollbackTo(RollbackEvent e);
    void reconcile(ChainState chainState);
    boolean isEnabled();

    /**
     * Store genesis UTXOs directly using Cardano protocol convention:
     * tx_hash = blake2b-256(address_hex_bytes), outputIndex = 0.
     * This matches how yaci-store and wallets derive genesis UTXO references.
     *
     * @param shelleyFunds  hex-encoded address → lovelace (from shelley initialFunds)
     * @param networkMagic  protocol magic for bech32 prefix selection
     * @param slot          slot number for the genesis block
     * @param blockNumber   block number (typically 0)
     * @param blockHash     block hash hex
     */
    default void storeGenesisUtxos(java.util.Map<String, Long> shelleyFunds, long networkMagic,
                                   long slot, long blockNumber, String blockHash) {
        // Default no-op for implementations that don't support direct genesis storage
    }

    /**
     * Reinitialize DB handles after a snapshot restore.
     * Called when the underlying RocksDB has been closed and reopened.
     */
    default void reinitialize() {
        // no-op for implementations that don't need it
    }

    /**
     * Inject a synthetic faucet UTXO directly into the unspent store.
     * The UTXO won't appear in any block's transactions — this is a devnet convenience.
     *
     * @param address   bech32 address to fund
     * @param lovelace  amount in lovelace
     * @return the synthetic tx hash (hex) used as the UTXO key
     */
    default String injectFaucetUtxo(String address, long lovelace) {
        throw new UnsupportedOperationException("Faucet injection not supported");
    }
}
