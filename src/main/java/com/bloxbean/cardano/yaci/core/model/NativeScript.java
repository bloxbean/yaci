package com.bloxbean.cardano.yaci.core.model;

import lombok.*;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@Builder
public class NativeScript {
    private int type;
    private String content;
}
