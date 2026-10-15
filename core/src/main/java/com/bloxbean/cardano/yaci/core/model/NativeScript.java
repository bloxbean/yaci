package com.bloxbean.cardano.yaci.core.model;

import lombok.*;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@Builder(toBuilder = true)
public class NativeScript {
    /** CBOR discriminator; see {@link NativeScriptType} for the supported wire values. */
    private int type;
    /** JSON representation, or null when this script could not be parsed safely. */
    private String content;
    /** Non-null when JSON conversion failed; the containing block is still returned. */
    private String parseError;

    /** Blake2b-224 script hash (policy ID), or null when original CBOR is unavailable. */
    private String hash;

    public NativeScript(int type, String content, String parseError) {
        this(type, content, parseError, null);
    }

    public NativeScript(int type, String content) {
        this(type, content, null);
    }
}
