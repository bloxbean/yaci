package com.bloxbean.cardano.yaci.core.model;

import lombok.*;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@Builder
public class NativeScript {
    private int type;
    /** JSON representation, or null when this script could not be parsed safely. */
    private String content;
    /** Non-null when parsing failed; the containing block is still returned. */
    private String parseError;

    public NativeScript(int type, String content) {
        this(type, content, null);
    }
}
