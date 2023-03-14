package com.bloxbean.cardano.yaci.core.model.byron;

import lombok.*;

import java.math.BigInteger;

import static com.bloxbean.cardano.yaci.core.util.Constants.BYRON_SLOTS_PER_EPOCH;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder
public class ByronEbBlockCons {
    private long epoch;
    private BigInteger difficulty;

    //Derive
    public long getAbsoluteSlot() {
        return epoch * BYRON_SLOTS_PER_EPOCH;
    }
}
