package com.bloxbean.cardano.yaci.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.math.BigInteger;

/**
 * The balance a reward account must have, in a Dijkstra transaction body's account balance intervals (keys 26 and 27):
 * either {@code exact}, or {@code inclusiveLower} and {@code exclusiveUpper}, either of which may be null.
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
public class AccountBalanceInterval {
    private BigInteger exact;
    private BigInteger inclusiveLower;
    private BigInteger exclusiveUpper;
}
