package com.bloxbean.cardano.yaci.core.model;

import lombok.*;

/**
 * The endorser block (EB) a ranking block certifies, set on a certifying block that Yaci's sync helpers deliver with
 * the EB's transactions: the block's first {@code txCount} transactions came from this EB, in EB order, and any after
 * them are the block's own.
 * <p>
 * Experimental: Dijkstra is not final; this may change in any release.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@EqualsAndHashCode
@Builder(toBuilder = true)
public class LeiosEndorserBlockRef {
    /** The EB's hash (of its references as encoded), in hex, as the parent block announced it. */
    private String ebHash;
    /** The slot of the block that announced the EB, the certifying block's parent. */
    private long ebSlot;
    /** The size of the EB's references in bytes, as announced. */
    private long ebSize;
    /** How many of the block's transactions, from the first, came from the EB. */
    private int txCount;
}
