package com.bloxbean.cardano.yaci.core.model.governance.actions;

import com.bloxbean.cardano.yaci.core.model.governance.GovActionType;
import lombok.*;

@Getter
@AllArgsConstructor
@EqualsAndHashCode
@ToString
public class InfoAction implements GovAction {
    private final GovActionType type = GovActionType.INFO_ACTION;
}
