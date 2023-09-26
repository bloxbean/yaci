package com.bloxbean.cardano.yaci.core.model.governance;

import lombok.*;

import java.util.LinkedHashMap;
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
    @Builder.Default
    private Map<Voter, Map<GovActionId, VotingProcedure>> voting = new LinkedHashMap<>();
}
