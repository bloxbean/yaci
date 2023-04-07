package com.bloxbean.cardano.yaci.core.model.byron;

import com.bloxbean.cardano.yaci.core.model.Epoch;
import com.bloxbean.cardano.yaci.core.model.byron.signature.BlockSignature;
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
public class ByronBlockCons {
    private Epoch slotId;
    private String pubKey;
    private BigInteger difficulty;
    private BlockSignature blockSig;

    //derive value
    @JsonIgnore
    public long getAbsoluteSlot() {
        return slotId.getEpoch() * BYRON_SLOTS_PER_EPOCH + slotId.getSlot();
    }
}
