package com.bloxbean.cardano.yaci.core.model.governance;

import lombok.*;

/**
 * anchor =
 *   [ anchor_url       : url
 *   , anchor_data_hash : $hash32
 *   ]
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder
public class Anchor {
    private String anchor_url;
    private String anchor_data_hash;
}
