package com.bloxbean.cardano.yaci.core.model;

import lombok.*;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@Builder
public class BootstrapWitness {
    private String publicKey;
    private String signature;
    public String chainCode;
    public String attributes;
}
