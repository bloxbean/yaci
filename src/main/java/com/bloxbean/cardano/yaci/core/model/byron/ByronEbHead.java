package com.bloxbean.cardano.yaci.core.model.byron;

import lombok.*;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder
public class ByronEbHead implements ByronHead<ByronEbBlockCons> {
    private long protocolMagic;
    private String prevBlock;
    private String bodyProof;
    private ByronEbBlockCons consensusData;
    private String extraData;

    //Derived Value
    private String blockHash;
}
