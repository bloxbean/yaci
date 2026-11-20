package com.bloxbean.cardano.yaci.core.protocol.localstate.queries;

import com.bloxbean.cardano.yaci.core.protocol.localstate.api.QueryResult;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

import java.math.BigInteger;
import java.util.Map;

@Getter
@AllArgsConstructor
@ToString
public class PoolDistrQueryResult implements QueryResult {
    /** Pools keyed by pool id (hex). */
    Map<String, IndividualPoolStake> poolDistributionMap;
    /** The total active stake in lovelace, from N2C V21; null before. */
    BigInteger totalActiveStake;
}

