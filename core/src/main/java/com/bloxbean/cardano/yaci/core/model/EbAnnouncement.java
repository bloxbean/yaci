package com.bloxbean.cardano.yaci.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

/**
 * The announcement of a Leios endorser block in a 12-item Dijkstra header.
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
public class EbAnnouncement {
    /** The hash of the endorser block's references, in hex. */
    private String hash;
    /** Their size in bytes. */
    private long size;
}
