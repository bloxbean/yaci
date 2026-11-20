package com.bloxbean.cardano.yaci.core.model.governance.actions;

import com.bloxbean.cardano.yaci.core.model.Credential;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionId;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionType;
import com.bloxbean.cardano.yaci.core.model.jackson.UnsignedLongJsonDeserializer;
import com.bloxbean.cardano.yaci.core.model.jackson.UnsignedLongJsonSerializer;
import com.bloxbean.cardano.yaci.core.types.UnitInterval;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.*;

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

    /**
     * Each new member's cold credential and the epoch its term ends. An epoch is a Word64 in the ledger: the value is
     * its 64 bits, so compare with {@link Long#compareUnsigned} and print with {@link Long#toUnsignedString}. JSON has
     * the unsigned number.
     */
    @JsonSerialize(contentUsing = UnsignedLongJsonSerializer.class)
    @JsonDeserialize(contentUsing = UnsignedLongJsonDeserializer.class)
    @Builder.Default
    private Map<Credential, Long> newMembersAndTerms = new LinkedHashMap<>();
    private UnitInterval threshold; //TODO?? Check the correct name.

}
