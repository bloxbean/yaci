package com.bloxbean.cardano.yaci.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@Builder
public class NativeScript {
    /** CBOR discriminator; see {@link NativeScriptType} for the supported wire values. */
    private int type;
    /** JSON representation, or null when this script could not be parsed safely. */
    private String content;
    /** Non-null when JSON conversion failed; the containing block is still returned. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String parseError;

    public NativeScript(int type, String content) {
        this(type, content, null);
    }
}
