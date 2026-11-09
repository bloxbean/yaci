package com.bloxbean.cardano.yaci.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

/**
 * The BLS key of a Dijkstra pool registration.
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
public class BlsKey {
    /** The public key, in hex. */
    private String publicKey;
    /** The proof of possession of its private key, in hex. */
    private String proofOfPossession;
}
