package com.bloxbean.cardano.yaci.core.model.governance.actions;

import com.bloxbean.cardano.yaci.core.model.governance.Constitution;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionId;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionType;
import lombok.*;

/**
 * new_constitution = (5, gov_action_id / null, constitution)
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@EqualsAndHashCode
@ToString
public class NewConstitution implements GovAction {
    private final GovActionType type = GovActionType.NEW_CONSTITUTION;

    private GovActionId govActionId;
    private Constitution constitution;
}
