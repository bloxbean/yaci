package com.bloxbean.cardano.yaci.node.api.events;

import com.bloxbean.cardano.yaci.events.api.Event;

/**
 * Third and final event in the three-phase epoch boundary sequence.
 *
 * <p>Maps to the <b>POOLREAP</b> rule of the Cardano ledger spec's EPOCH rule
 * (shelley-ledger.pdf §17.4). This is where retiring pools are reaped: their deposits
 * are refunded to registered reward addresses (or sent to treasury if the reward address
 * is unregistered).</p>
 *
 * <p>Running POOLREAP <em>after</em> the snapshot ({@link EpochTransitionEvent}) ensures
 * that the 500 ADA deposit refunds per retired pool do not inflate the snapshot's
 * active stake calculation.</p>
 *
 * <h3>Epoch boundary event order (mirrors ledger spec EPOCH rule):</h3>
 * <ol>
 *   <li>{@link PreEpochTransitionEvent}  — Reward calculation, AdaPot update, param finalization</li>
 *   <li>{@link EpochTransitionEvent}     — <b>SNAP</b>: delegation/stake snapshot (mark snapshot)</li>
 *   <li>{@code PostEpochTransitionEvent} — <b>POOLREAP</b>: pool deposit refunds, pool retirement</li>
 * </ol>
 *
 * <p>All three events fire before {@link BlockAppliedEvent} for the first block of the new epoch.</p>
 *
 * @param previousEpoch the epoch that just ended
 * @param newEpoch      the epoch that is beginning
 * @param slot          slot of the first block in the new epoch
 * @param blockNumber   block number of the first block in the new epoch
 */
public record PostEpochTransitionEvent(int previousEpoch, int newEpoch, long slot, long blockNumber)
        implements Event {}
