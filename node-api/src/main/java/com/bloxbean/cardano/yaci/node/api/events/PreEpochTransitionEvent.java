package com.bloxbean.cardano.yaci.node.api.events;

import com.bloxbean.cardano.yaci.events.api.Event;

/**
 * First event in the three-phase epoch boundary sequence, published BEFORE the first
 * block of a new epoch is applied.
 *
 * <p>Maps to the <b>reward calculation and AdaPot update</b> phase of the Cardano ledger
 * spec's EPOCH rule (shelley-ledger.pdf §17.4). This is where rewards are computed from
 * the previous epoch's data and protocol parameters are finalized.</p>
 *
 * <h3>Epoch boundary event order (mirrors ledger spec EPOCH rule):</h3>
 * <ol>
 *   <li>{@code PreEpochTransitionEvent}  — Reward calculation, AdaPot update, param finalization</li>
 *   <li>{@link EpochTransitionEvent}     — <b>SNAP</b>: delegation/stake snapshot (mark snapshot)</li>
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
public record PreEpochTransitionEvent(int previousEpoch, int newEpoch, long slot, long blockNumber)
        implements Event {}
