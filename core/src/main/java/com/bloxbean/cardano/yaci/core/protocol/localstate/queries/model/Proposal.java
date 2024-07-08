package com.bloxbean.cardano.yaci.core.protocol.localstate.queries.model;

import com.bloxbean.cardano.yaci.core.model.certs.StakePoolId;
import com.bloxbean.cardano.yaci.core.model.governance.Drep;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionId;
import com.bloxbean.cardano.yaci.core.model.governance.ProposalProcedure;
import com.bloxbean.cardano.yaci.core.model.governance.Vote;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
@Builder
public class Proposal {
    private GovActionId govActionId;
    // TODO: committees votes;
    private Map<Drep, Vote> dRepVotes;
    private Map<StakePoolId, Vote> stakePoolVotes;
    private ProposalProcedure proposalProcedure;
    private Integer expiredAfter;
    private Integer proposedIn;
}
