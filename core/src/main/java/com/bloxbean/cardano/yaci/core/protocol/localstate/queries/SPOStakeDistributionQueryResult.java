package com.bloxbean.cardano.yaci.core.protocol.localstate.queries;

import com.bloxbean.cardano.yaci.core.model.certs.StakePoolId;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.QueryResult;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

@Getter
@ToString
@AllArgsConstructor
public class SPOStakeDistributionQueryResult implements QueryResult {
    Map<StakePoolId, BigInteger> spoStakeMap;

    public SPOStakeDistributionQueryResult() {
        this.spoStakeMap = new HashMap<>();
    }

    public void addStakePoolStake(StakePoolId stakePoolId, BigInteger amount) {
        spoStakeMap.put(stakePoolId, amount);
    }
}
