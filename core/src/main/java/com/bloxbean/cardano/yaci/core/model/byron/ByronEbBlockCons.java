package com.bloxbean.cardano.yaci.core.model.byron;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
    @JsonIgnore
    public long getAbsoluteSlot() {
        return epoch * BYRON_SLOTS_PER_EPOCH;
    }
}
