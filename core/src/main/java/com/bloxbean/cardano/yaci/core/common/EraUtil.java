package com.bloxbean.cardano.yaci.core.common;

import com.bloxbean.cardano.client.transaction.raw.BlockEnvelope;
import com.bloxbean.cardano.yaci.core.model.Era;

public class EraUtil {

    /** Read only the era in [era, block], so nested block data reaches the guarded block parser. */
    public static int getEraValue(byte[] blockBytes) {
        long eraIndex = BlockEnvelope.eraIndexOf(blockBytes);
        // An index of 2^63 or more reads as negative.
        if (eraIndex < 0) throw new ArithmeticException("Era index out of range: " + Long.toUnsignedString(eraIndex));
        return Math.toIntExact(eraIndex);
    }

    public static Era getEra(int value) {
        switch (value) {
            case 0:
            case 1:
                return Era.Byron;
            case 2:
                return Era.Shelley;
            case 3:
                return Era.Allegra;
            case 4:
                return Era.Mary;
            case 5:
                return Era.Alonzo;
            case 6:
                return Era.Babbage;
            case 7:
                return Era.Conway;
            default:
                return null;
        }
    }
}
