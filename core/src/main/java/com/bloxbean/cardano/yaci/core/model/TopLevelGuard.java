package com.bloxbean.cardano.yaci.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

/**
 * An entry of a Dijkstra transaction body's required top-level guards (key 24): a guard the top-level transaction must
 * have, and the datum given to it.
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
public class TopLevelGuard {
    private Credential credential;
    /** The datum, from its bytes as encoded, or null if the entry has none. */
    private Datum datum;
}
