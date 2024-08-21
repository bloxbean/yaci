package com.bloxbean.cardano.yaci.core.protocol.localstate.queries.model;

import com.bloxbean.cardano.yaci.core.model.governance.GovActionId;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.List;

@Getter
@Setter
@Builder
@ToString
public class RatifyState {
    private List<Proposal> enactedGovActions;
    private List<GovActionId> expiredGovActions;
    private EnactState nextEnactState;
    private Boolean ratificationDelayed;
}
