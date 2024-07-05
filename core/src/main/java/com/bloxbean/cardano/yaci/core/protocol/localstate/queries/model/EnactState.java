package com.bloxbean.cardano.yaci.core.protocol.localstate.queries.model;

import com.bloxbean.cardano.yaci.core.model.ProtocolParamUpdate;
import com.bloxbean.cardano.yaci.core.model.governance.Committee;
import com.bloxbean.cardano.yaci.core.model.governance.Constitution;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionId;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
@Builder
public class EnactState {
    private Committee committee;
    private Constitution constitution;
    private ProtocolParamUpdate currentPParams;
    private ProtocolParamUpdate prevPParams;
    private Map<ProposalType, GovActionId> prevGovActionIds;
}
