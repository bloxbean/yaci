package com.bloxbean.cardano.yaci.core.model;

import lombok.*;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@Builder
public class Datum {
    private String cbor;
    private String json;
}
