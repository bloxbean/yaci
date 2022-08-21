package com.bloxbean.cardano.yaci.core.model.byron;

import lombok.*;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder
public class ByronEbHead implements ByronHead {
    private long protocolMagic;
    private String prevBlock;
    private String bodyProof;
    private ByronBlockCons consensusData;
    private String extraData;
}
