package com.bloxbean.cardano.yaci.core.model.conway;

import lombok.*;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder
/**
 * voting_procedure =
 *        [ vote, anchor / null]
 */
public class VotingProcedure {
    private Vote vote;
    private Anchor anchor;
}
