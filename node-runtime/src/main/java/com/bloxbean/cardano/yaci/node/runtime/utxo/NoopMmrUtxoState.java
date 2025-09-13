package com.bloxbean.cardano.yaci.node.runtime.utxo;

import com.bloxbean.cardano.yaci.node.api.utxo.UtxoMmrState;
import com.bloxbean.cardano.yaci.node.api.utxo.UtxoState;
import com.bloxbean.cardano.yaci.node.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yaci.node.api.utxo.model.Utxo;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Minimal no-op UTXO state that also exposes MMR shape. Used as a fallback
 * when MMR is configured but the store is not initialized yet.
 */
public final class NoopMmrUtxoState implements UtxoState, UtxoMmrState {
    @Override public List<Utxo> getUtxosByAddress(String bech32OrHexAddress, int page, int pageSize) { return Collections.emptyList(); }
    @Override public List<Utxo> getUtxosByPaymentCredential(String credentialHexOrAddress, int page, int pageSize) { return Collections.emptyList(); }
    @Override public Optional<Utxo> getUtxo(Outpoint outpoint) { return Optional.empty(); }
    @Override public boolean isEnabled() { return true; }

    @Override public long getMmrLeafCount() { return 0; }
    @Override public String getMmrRootHex() { return "0".repeat(64); }
    @Override public Optional<Long> getLeafIndex(Outpoint outpoint) { return Optional.empty(); }
    @Override public Optional<com.bloxbean.cardano.yaci.node.api.utxo.model.MmrProof> getProof(long leafIndex) { return Optional.empty(); }
}
