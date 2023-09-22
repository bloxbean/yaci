package com.bloxbean.cardano.yaci.core.model.conway;

import lombok.*;

import java.util.Map;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder
/**
 * voting_procedures = { + voter => { + gov_action_id => voting_procedure } }
 */
public class VotingProcedures {
    private Map<Voter, Map<GovActionId, VotingProcedure>> voting;
}
