package com.bloxbean.cardano.yaci.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

/**
 * The Leios certificate of a Dijkstra block body.
 * <p>
 * Experimental: Dijkstra is not final; this may change in any release.
 */
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
@NoArgsConstructor
@AllArgsConstructor
@ToString
@EqualsAndHashCode
@Builder(toBuilder = true)
public class LeiosCertificate {
    /** The bit field of the committee members who signed, in hex. */
    private String signers;
    /** Their aggregate signature, in hex. */
    private String signature;
}
