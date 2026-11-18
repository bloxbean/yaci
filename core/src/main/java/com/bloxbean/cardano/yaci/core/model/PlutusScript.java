package com.bloxbean.cardano.yaci.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@Builder
public class PlutusScript {
    private PlutusScriptType type;
    private String content;
    /**
     * Blake2b-224 of the language byte and the script's bytes (the payload of its byte string), as the ledger hashes
     * it. Null for objects built by hand and for a type without a language byte. The bytes are hashed, not checked.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String scriptHash;

    public PlutusScript(PlutusScriptType type, String content) {
        this(type, content, null);
    }
}
