package com.bloxbean.cardano.yaci.core.model.governance;

import lombok.*;

/**
 * constitution =
 *   [ anchor
 *   , scripthash / null
 *   ]
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@EqualsAndHashCode
@ToString
public class Constitution {
    private Anchor anchor;
    private String scripthash;
}
