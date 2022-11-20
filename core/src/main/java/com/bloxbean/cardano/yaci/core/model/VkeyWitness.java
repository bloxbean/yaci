package com.bloxbean.cardano.yaci.core.model;

import lombok.*;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder
public class VkeyWitness {
    private String key;
    private String signature;
}
