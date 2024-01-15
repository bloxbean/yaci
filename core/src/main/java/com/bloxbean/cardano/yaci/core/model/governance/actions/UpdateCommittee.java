package com.bloxbean.cardano.yaci.core.model.governance.actions;

import com.bloxbean.cardano.yaci.core.model.Credential;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionId;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionType;
import lombok.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * {@literal
 * update_committee = (4, gov_action_id / null, set<committee_cold_credential>, { committee_cold_credential => epoch }, unit_interval)
 * }
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@EqualsAndHashCode
@ToString
public class UpdateCommittee implements GovAction {
    private final GovActionType type = GovActionType.UPDATE_COMMITTEE;

    private GovActionId govActionId;
    @Builder.Default
    private Set<Credential> membersForRemoval = new LinkedHashSet<>();

    @Builder.Default
    private Map<Credential, Integer> newMembersAndTerms = new LinkedHashMap<>();
    private BigDecimal quorumThreshold; //TODO?? Check the correct name.

}
