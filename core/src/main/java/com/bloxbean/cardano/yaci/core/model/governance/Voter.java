package com.bloxbean.cardano.yaci.core.model.governance;

import com.bloxbean.cardano.yaci.core.model.jackson.VoterDeserializer;
import com.bloxbean.cardano.yaci.core.model.jackson.VoterSerializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.*;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder
@JsonDeserialize(keyUsing = VoterDeserializer.class)
@JsonSerialize(keyUsing = VoterSerializer.class)
public class Voter {
    private VoterType type;
    private String hash; //key hash or script hash
}
