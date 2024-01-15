package com.bloxbean.cardano.yaci.core.model.governance.actions;

import com.bloxbean.cardano.yaci.core.model.ProtocolParamUpdate;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionId;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionType;
import lombok.*;

/**
 * parameter_change_action = (0, gov_action_id / null, protocol_param_update)
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@EqualsAndHashCode
@ToString
public class ParameterChangeAction implements GovAction {
    private final GovActionType type = GovActionType.PARAMETER_CHANGE_ACTION;

    private GovActionId govActionId;
    private ProtocolParamUpdate protocolParamUpdate;
}
