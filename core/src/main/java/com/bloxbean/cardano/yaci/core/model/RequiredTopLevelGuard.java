package com.bloxbean.cardano.yaci.core.model;

import lombok.*;

/**
 * Dijkstra era: an entry of {@code required_top_level_guards} (tx body key 24) of a sub-transaction,
 * {@code credential => plutus_data / nil}.
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@EqualsAndHashCode
@ToString
public class RequiredTopLevelGuard {
    private Credential credential;
    //plutus_data CBOR hex, null when nil
    private String datum;
}
