package com.bloxbean.cardano.yaci.core.model.leios;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Shape-tolerant Leios vote model. Unknown wire shapes retain only their raw CBOR and format.
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder(toBuilder = true)
public class LeiosVote {
    private LeiosVoteFormat format;
    private Long slot;
    private String ebHash;
    private String announcingRbHash;
    private Integer voterId;
    private String voteSignature;
    private String cbor;
}
