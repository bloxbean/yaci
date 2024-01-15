package com.bloxbean.cardano.yaci.core.model.governance.actions;

import com.bloxbean.cardano.yaci.core.model.ProtocolVersion;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionId;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionType;
import lombok.*;

/**
 * hard_fork_initiation_action = (1, gov_action_id / null, [protocol_version])
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@EqualsAndHashCode
@ToString
public class HardForkInitiationAction implements GovAction {
    private final GovActionType type = GovActionType.HARD_FORK_INITIATION_ACTION;

    private GovActionId govActionId;
    private ProtocolVersion protocolVersion;
}
