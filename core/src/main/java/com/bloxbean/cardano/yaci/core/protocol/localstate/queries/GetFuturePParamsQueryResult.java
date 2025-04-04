package com.bloxbean.cardano.yaci.core.protocol.localstate.queries;

import com.bloxbean.cardano.yaci.core.model.ProtocolParamUpdate;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.QueryResult;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

@Getter
@AllArgsConstructor
@ToString
public class GetFuturePParamsQueryResult implements QueryResult {
    private ProtocolParamUpdate futurePParams;
}
