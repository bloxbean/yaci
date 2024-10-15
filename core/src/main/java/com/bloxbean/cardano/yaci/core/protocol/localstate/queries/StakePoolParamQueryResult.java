package com.bloxbean.cardano.yaci.core.protocol.localstate.queries;

import com.bloxbean.cardano.yaci.core.model.PoolParams;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.QueryResult;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

@Getter
@ToString
@AllArgsConstructor
public class StakePoolParamQueryResult implements QueryResult {
    private  java.util.Map<String, PoolParams> poolParams;
}
