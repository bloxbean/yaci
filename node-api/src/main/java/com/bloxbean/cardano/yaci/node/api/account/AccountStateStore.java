package com.bloxbean.cardano.yaci.node.api.account;

import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.node.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yaci.node.api.events.RollbackEvent;

import java.math.BigInteger;
import java.util.List;

/**
 * Write interface for account state storage.
 * Extends {@link LedgerStateProvider} with block application and rollback support.
 */
public interface AccountStateStore extends LedgerStateProvider {

    void applyBlock(BlockAppliedEvent event);

    void rollbackTo(RollbackEvent event);

    /**
     * Reconcile store state with chain tip on startup.
     * Same pattern as DefaultUtxoStore.reconcile().
     */
    void reconcile(ChainState chainState);

    boolean isEnabled();

    /**
     * Handle pre-epoch transition: reward calculation, AdaPot update, protocol param finalization.
     * Maps to the reward/param phase of the ledger spec's EPOCH rule, before SNAP.
     * Called BEFORE the first block of the new epoch is applied.
     *
     * @see com.bloxbean.cardano.yaci.node.api.events.PreEpochTransitionEvent
     */
    default void handleEpochTransition(int previousEpoch, int newEpoch) {}

    /**
     * Handle epoch transition snapshot: create delegation/stake mark snapshot (SNAP).
     * Maps to the <b>SNAP</b> rule of the ledger spec's EPOCH rule (shelley-ledger.pdf §17.4).
     * Called AFTER rewards are distributed but BEFORE pool deposit refunds (POOLREAP),
     * ensuring refunds do not inflate the snapshot's active stake.
     *
     * @see com.bloxbean.cardano.yaci.node.api.events.EpochTransitionEvent
     */
    default void handleEpochTransitionSnapshot(int previousEpoch, int newEpoch) {}

    /**
     * Handle post-epoch transition: POOLREAP (pool deposit refunds and pool retirement).
     * Maps to the <b>POOLREAP</b> rule of the ledger spec's EPOCH rule (shelley-ledger.pdf §17.4).
     * Called AFTER the snapshot is taken but BEFORE block application.
     *
     * @see com.bloxbean.cardano.yaci.node.api.events.PostEpochTransitionEvent
     */
    default void handlePostEpochTransition(int previousEpoch, int newEpoch) {}

    /**
     * Reinitialize after snapshot restore (refresh DB handles).
     */
    default void reinitialize() {}

    // --- Listing / iteration (page is 1-based, count capped at 100) ---

    record StakeRegistrationEntry(int credType, String credentialHash, BigInteger reward, BigInteger deposit) {}
    record PoolDelegationEntry(int credType, String credentialHash, String poolHash, long slot, int txIdx, int certIdx) {}
    record DRepDelegationEntry(int credType, String credentialHash, int drepType, String drepHash, long slot, int txIdx, int certIdx) {}
    record PoolEntry(String poolHash, BigInteger deposit) {}
    record PoolRetirementEntry(String poolHash, long retirementEpoch) {}

    default List<StakeRegistrationEntry> listStakeRegistrations(int page, int count) { return List.of(); }
    default List<PoolDelegationEntry> listPoolDelegations(int page, int count) { return List.of(); }
    default List<DRepDelegationEntry> listDRepDelegations(int page, int count) { return List.of(); }
    default List<PoolEntry> listPools(int page, int count) { return List.of(); }
    default List<PoolRetirementEntry> listPoolRetirements(int page, int count) { return List.of(); }
}
