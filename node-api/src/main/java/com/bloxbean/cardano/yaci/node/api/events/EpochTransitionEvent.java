package com.bloxbean.cardano.yaci.node.api.events;

import com.bloxbean.cardano.yaci.events.api.Event;

/**
 * Second event in the three-phase epoch boundary sequence.
 *
 * <p>Maps to the <b>SNAP</b> rule of the Cardano ledger spec's EPOCH rule
 * (shelley-ledger.pdf §17.4). This is where the delegation/stake mark snapshot is taken,
 * capturing the current reward balances, stake delegations, and pool registrations.</p>
 *
 * <p>The snapshot is taken <em>after</em> rewards have been distributed
 * ({@link PreEpochTransitionEvent}) but <em>before</em> pool deposit refunds
 * ({@link PostEpochTransitionEvent}), ensuring that retired pool deposits do not
 * inflate the snapshot's active stake.</p>
 *
 * <h3>Epoch boundary event order (mirrors ledger spec EPOCH rule):</h3>
 * <ol>
 *   <li>{@link PreEpochTransitionEvent}  — Reward calculation, AdaPot update, param finalization</li>
 *   <li>{@code EpochTransitionEvent}     — <b>SNAP</b>: delegation/stake snapshot (mark snapshot)</li>
 *   <li>{@link PostEpochTransitionEvent} — <b>POOLREAP</b>: pool deposit refunds, pool retirement</li>
 * </ol>
 *
 * <p>All three events fire before {@link BlockAppliedEvent} for the first block of the new epoch.</p>
 *
 * @param previousEpoch the epoch that just ended
 * @param newEpoch      the epoch that is beginning
 * @param slot          slot of the first block in the new epoch
 * @param blockNumber   block number of the first block in the new epoch
 */
public record EpochTransitionEvent(int previousEpoch, int newEpoch, long slot, long blockNumber)
        implements Event {}
