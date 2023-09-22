package com.bloxbean.cardano.yaci.core.model.conway;

import lombok.*;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder
public class Voter {
    private VoterType type;
    private String hash; //key hash or script hash
}
