package com.bloxbean.cardano.yaci.core.protocol.localstate.queries;

import com.bloxbean.cardano.yaci.core.protocol.localstate.api.QueryResult;
import com.bloxbean.cardano.yaci.core.protocol.localstate.queries.model.PoolStakeSnapshot;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.math.BigInteger;
import java.util.Map;

/** The ledger's StakeSnapshots: each pool's mark, set and go stake, and the three totals, in lovelace. */
@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class StakeSnapshotQueryResult implements QueryResult {
    /** Pools keyed by pool id (hex). */
    private Map<String, PoolStakeSnapshot> poolStakeSnapshots;
    private BigInteger markTotal;
    private BigInteger setTotal;
    private BigInteger goTotal;
}
