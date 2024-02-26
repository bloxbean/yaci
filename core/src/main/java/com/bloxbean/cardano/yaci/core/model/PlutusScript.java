package com.bloxbean.cardano.yaci.core.model;

import lombok.*;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@Builder
public class PlutusScript {
    private PlutusScriptType type;
    private String content;
}
