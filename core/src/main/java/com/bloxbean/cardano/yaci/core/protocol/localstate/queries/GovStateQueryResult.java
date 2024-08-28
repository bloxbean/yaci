package com.bloxbean.cardano.yaci.core.protocol.localstate.queries;

import com.bloxbean.cardano.yaci.core.model.ProtocolParamUpdate;
import com.bloxbean.cardano.yaci.core.model.governance.Committee;
import com.bloxbean.cardano.yaci.core.model.governance.Constitution;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.QueryResult;
import com.bloxbean.cardano.yaci.core.protocol.localstate.queries.model.Proposal;
import com.bloxbean.cardano.yaci.core.protocol.localstate.queries.model.RatifyState;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.List;

@Getter
@Setter
@ToString
public class GovStateQueryResult implements QueryResult {
    private Committee committee;
    private Constitution constitution;
    private ProtocolParamUpdate currentPParams;
    private ProtocolParamUpdate futurePParams;
    private ProtocolParamUpdate previousPParams;
    private RatifyState nextRatifyState;
    private List<Proposal> proposals;
}
