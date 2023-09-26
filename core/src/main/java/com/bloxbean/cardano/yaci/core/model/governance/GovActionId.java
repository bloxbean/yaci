package com.bloxbean.cardano.yaci.core.model.governance;

import lombok.*;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder
public class GovActionId {
    private String transactionId;
    private Integer gov_action_index;
}
