package com.bloxbean.cardano.yaci.core.model.byron;

import com.bloxbean.cardano.yaci.core.model.Epoch;
import lombok.*;

import java.math.BigInteger;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder
public class ByronBlockCons {
    private Epoch slotId;
    private String pubKey;
    private BigInteger difficulty;
    private String blockSig;
}
