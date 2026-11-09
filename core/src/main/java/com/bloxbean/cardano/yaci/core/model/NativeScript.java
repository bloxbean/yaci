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
    /**
     * Blake2b-224 of 0x00 and the script's original CBOR. Null where the original bytes are not available: entry points
     * taking a decoded DataItem, and objects built by hand. It is never computed from a re-encoding.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String scriptHash;

    public NativeScript(int type, String content) {
        this(type, content, null);
    }

    public NativeScript(int type, String content, String parseError) {
        this(type, content, parseError, null);
    }
}
