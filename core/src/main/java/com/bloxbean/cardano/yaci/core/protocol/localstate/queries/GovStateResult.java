package com.bloxbean.cardano.yaci.core.protocol.localstate.queries;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.yaci.core.model.governance.Committee;
import com.bloxbean.cardano.yaci.core.model.governance.Constitution;
import com.bloxbean.cardano.yaci.core.model.governance.ProposalProcedure;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.QueryResult;
import com.bloxbean.cardano.yaci.core.protocol.localstate.queries.model.RatifyState;
import lombok.Getter;

import java.util.List;

@Getter
public class GovStateResult implements QueryResult {
    private Committee committee;
    private Constitution constitution;
    private ProtocolParams currentPParams;
    private ProtocolParams futurePParams;
    private ProtocolParams previousPParams;
    private RatifyState nextRatifyState;
    private List<ProposalProcedure> proposals;
}
