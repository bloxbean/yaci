package com.bloxbean.cardano.yaci.core.model;

import lombok.*;

import java.math.BigInteger;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder(toBuilder = true)
public class Amount {
    private String unit;
    private String policyId;
    //utf-8 assetname
    private String assetName;
    private byte[] assetNameBytes;
    private BigInteger quantity;
}
