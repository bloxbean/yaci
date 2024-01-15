package com.bloxbean.cardano.yaci.core.model.governance.actions;

import com.bloxbean.cardano.yaci.core.model.governance.GovActionId;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionType;
import lombok.*;

/**
 * no_confidence = (3, gov_action_id / null)
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@EqualsAndHashCode
@ToString
public class NoConfidence implements GovAction {
    private final GovActionType type = GovActionType.NO_CONFIDENCE;

    private GovActionId govActionId;
}
