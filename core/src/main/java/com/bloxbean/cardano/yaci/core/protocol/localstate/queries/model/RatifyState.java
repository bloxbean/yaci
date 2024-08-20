package com.bloxbean.cardano.yaci.core.protocol.localstate.queries.model;

import com.bloxbean.cardano.yaci.core.model.governance.GovActionId;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@Builder
public class RatifyState {
    private List<Proposal> enactedGovActions;
    private List<GovActionId> expiredGovActions;
    private EnactState nextEnactState;
    private Boolean ratificationDelayed;
}
