package com.bloxbean.cardano.yaci.core.model;

import lombok.*;

/**
 * One entry of a transaction's metadata, as encoded on chain.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@EqualsAndHashCode
@Builder
public class MetadataLabel {
    /** The label in decimal (an unsigned 64-bit integer); null if the key is not an untagged unsigned integer. */
    private String label;
    /**
     * The entry as a one-entry map, {@code a1} followed by the key's and the value's bytes as received. Nothing in it is
     * re-encoded, so key order, repeated keys and indefinite lengths inside the value stay as on chain.
     */
    private String cbor;
}
