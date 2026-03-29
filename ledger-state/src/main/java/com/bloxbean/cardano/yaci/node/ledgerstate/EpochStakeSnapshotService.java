package com.bloxbean.cardano.yaci.node.ledgerstate;

import com.bloxbean.cardano.yaci.node.api.utxo.UtxoState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.Map;

/**
 * Enhances delegation snapshots with UTXO-derived stake amounts.
 * <p>
 * At epoch boundary, iterates all UTXOs to compute per-credential lovelace balances,
 * then merges with existing delegation snapshot to produce a full stake distribution.
 * <p>
 * This is disabled by default. When enabled, the epoch delegation snapshot value
 * changes from {0: poolHash} to {0: poolHash, 1: amount}.
 */
public class EpochStakeSnapshotService {
    private static final Logger log = LoggerFactory.getLogger(EpochStakeSnapshotService.class);

    private final UtxoBalanceAggregator aggregator;
    private volatile boolean enabled;

    public EpochStakeSnapshotService(boolean enabled) {
        this.aggregator = new UtxoBalanceAggregator();
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Aggregate UTXO balances by stake credential.
     * Called at epoch boundary before snapshot creation.
     *
     * @param utxoState       the UTXO store
     * @param pointerResolver optional resolver for pointer addresses
     * @return map from credential key to lovelace balance, or empty map if disabled
     */
    public Map<UtxoBalanceAggregator.CredentialKey, BigInteger> aggregateStakeBalances(
            UtxoState utxoState, PointerAddressResolver pointerResolver) {
        if (!enabled) return Map.of();
        return aggregator.aggregateBalances(utxoState, pointerResolver);
    }

    /**
     * Aggregate UTXO balances by stake credential (without pointer resolution).
     */
    public Map<UtxoBalanceAggregator.CredentialKey, BigInteger> aggregateStakeBalances(UtxoState utxoState) {
        return aggregateStakeBalances(utxoState, null);
    }
}
