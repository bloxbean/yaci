package com.bloxbean.cardano.yaci.core.protocol.localstate.queries;

import com.bloxbean.cardano.yaci.core.protocol.localstate.api.QueryResult;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

import java.util.Map;

@Getter
@AllArgsConstructor
@ToString
public class StakeDistributionQueryResult implements QueryResult {
    Map<String, IndividualPoolStake> stakeDistributionMap;
}

