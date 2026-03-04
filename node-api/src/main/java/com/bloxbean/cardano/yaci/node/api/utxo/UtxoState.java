package com.bloxbean.cardano.yaci.node.api.utxo;

import com.bloxbean.cardano.yaci.node.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yaci.node.api.utxo.model.Utxo;

import java.util.List;
import java.util.Optional;

/**
 * Public interface for UTXO storage and queries.
 * Implementations live in node-runtime (e.g., Classic, MMR).
 */
public interface UtxoState {

    /**
     * Return current unspent UTXOs for a bech32 or hex address.
     * Pagination is 1-based; pageSize must be > 0.
     */
    List<Utxo> getUtxosByAddress(String bech32OrHexAddress, int page, int pageSize);

    /**
     * Return current unspent UTXOs for a payment credential (28-byte hash in hex),
     * or an address (bech32/hex) from which the payment credential is derived.
     * Pagination is 1-based; pageSize must be > 0.
     */
    List<Utxo> getUtxosByPaymentCredential(String credentialHexOrAddress, int page, int pageSize);

    /**
     * Return a specific UTXO by outpoint if it is currently unspent.
     */
    Optional<Utxo> getUtxo(Outpoint outpoint);

    /**
     * Convenience outpoint lookup.
     */
    default Optional<Utxo> getUtxo(String txHash, int index) {
        return getUtxo(new Outpoint(txHash, index));
    }

    /**
     * Whether UTXO state is enabled and actively maintained.
     */
    boolean isEnabled();
}
