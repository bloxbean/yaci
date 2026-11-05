package com.bloxbean.cardano.yaci.core.model.serializers.util;

import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @deprecated {@link com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer} reads the block through CCL's
 * reader and sets the source bytes on its models. This is kept for outside callers.
 */
@Deprecated
public class AuxDataExtractor {
    private static final int BLOCK = 1;
    private static final int AUXILIARY_DATA = 3;

    /**
     * @param blockBytes a block in its envelope; only what comes before its auxiliary data and that map are read
     * @return the auxiliary data as encoded, by transaction index
     */
    public static Map<Integer, byte[]> getAuxDataFromBlock(byte[] blockBytes) {
        Map<Integer, byte[]> auxDataMap = new LinkedHashMap<>();
        for (Map.Entry<CborSpan, CborSpan> entry : CborSpan.arrayItem(blockBytes, BLOCK, AUXILIARY_DATA).entries()) {
            int txIndex = CborSerializationUtil.toInt(CborSerializationUtil.deserializeOne(entry.getKey().bytes()));
            auxDataMap.put(txIndex, entry.getValue().bytes());
        }
        return auxDataMap;
    }
}
