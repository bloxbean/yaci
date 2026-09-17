package com.bloxbean.cardano.yaci.core.common;

import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.serializers.util.CborSlice;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import lombok.SneakyThrows;

public class EraUtil {

    /** Read only the era in [era, block], so nested block data reaches the guarded block parser. */
    @SneakyThrows
    public static int getEraValue(byte[] blockBytes) {
        byte[] eraBytes = CborSlice.arrayItem(blockBytes, 0).bytes();
        return ((UnsignedInteger) CborSerializationUtil.deserializeOne(eraBytes)).getValue().intValueExact();
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
