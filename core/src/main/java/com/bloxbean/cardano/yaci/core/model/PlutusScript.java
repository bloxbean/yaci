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
    /** Blake2b-224 hash of the language prefix and payload, or null when unavailable. */
    private String hash;

    public PlutusScript(PlutusScriptType type, String content) {
        this(type, content, null);
    }
}
