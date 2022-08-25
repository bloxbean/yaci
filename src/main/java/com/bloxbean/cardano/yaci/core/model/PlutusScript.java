package com.bloxbean.cardano.yaci.core.model;

import lombok.*;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@Builder
public class PlutusScript {
    private String type;
    private String content;
}
