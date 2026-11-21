package com.bloxbean.cardano.yaci.core.protocol.localstate.queries.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

import java.math.BigInteger;

/** A pool's stake in lovelace in the mark, set and go snapshots (ledger StakeSnapshot). */
@Getter
@AllArgsConstructor
@ToString
public class PoolStakeSnapshot {
    private BigInteger markPool;
    private BigInteger setPool;
    private BigInteger goPool;
}
