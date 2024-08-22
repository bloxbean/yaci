package com.bloxbean.cardano.yaci.core.protocol.localstate.queries.model;

import com.bloxbean.cardano.yaci.core.model.Credential;
import com.bloxbean.cardano.yaci.core.model.certs.StakePoolId;
import com.bloxbean.cardano.yaci.core.model.governance.Drep;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionId;
import com.bloxbean.cardano.yaci.core.model.governance.ProposalProcedure;
import com.bloxbean.cardano.yaci.core.model.governance.Vote;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.Map;

@Getter
@Setter
@Builder
@ToString
public class Proposal {
    private GovActionId govActionId;
    private Map<Credential, Vote> committeeVotes;
    private Map<Drep, Vote> dRepVotes;
    private Map<StakePoolId, Vote> stakePoolVotes;
    private ProposalProcedure proposalProcedure;
    private Integer expiredAfter;
    private Integer proposedIn;
}
