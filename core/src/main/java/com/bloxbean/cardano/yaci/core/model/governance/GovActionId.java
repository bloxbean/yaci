package com.bloxbean.cardano.yaci.core.model.governance;

import com.bloxbean.cardano.yaci.core.model.jackson.GovActionIdDeserializer;
import com.bloxbean.cardano.yaci.core.model.jackson.GovActionIdSerializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.*;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder
@JsonDeserialize(keyUsing = GovActionIdDeserializer.class)
@JsonSerialize(keyUsing = GovActionIdSerializer.class)
public class GovActionId {
    private String transactionId;
    private Integer gov_action_index;
}
