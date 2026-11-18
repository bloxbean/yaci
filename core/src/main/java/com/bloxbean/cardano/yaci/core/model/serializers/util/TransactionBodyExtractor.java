package com.bloxbean.cardano.yaci.core.model.serializers.util;

import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.AdditionalInformation;
import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.Tuple;

import java.util.ArrayList;
import java.util.List;

/**
 * This is only for Shelley and post Shelley era blocks.
 *
 * @deprecated {@link com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer} reads the block through CCL's
 * reader and sets the source bytes on its models; CCL's {@code RawBlock} gives each item's source bytes.
 */
@Deprecated(since = "0.5.0", forRemoval = true)
public class TransactionBodyExtractor {
    private static final int BLOCK = 1;
    private static final int TRANSACTION_BODIES = 1;

    public static final int INFINITY = -1;

    /**
     * @param blockBody a block in its envelope; only what comes before its transaction bodies and those are read
     * @return each transaction body, decoded and as encoded
     */
    public static List<Tuple<DataItem, byte[]>> getTxBodiesFromBlock(byte[] blockBody) {
        List<Tuple<DataItem, byte[]>> txBodyTuples = new ArrayList<>();
        for (CborSpan txBody : CborSpan.arrayItem(blockBody, BLOCK, TRANSACTION_BODIES).items()) {
            byte[] txBodyRaw = txBody.bytes();
            txBodyTuples.add(new Tuple<>(CborSerializationUtil.deserializeOne(txBodyRaw), txBodyRaw));
        }
        return txBodyTuples;
    }

    public static byte[] getSymbolBytes(int start, byte[] src){
        if(start >= src.length){
            return new byte[]{};
        }
        byte[] symbol = new byte[src.length - start];
        System.arraycopy(src,start,symbol,0,src.length - start);
        return symbol;
    }

    public static long getLength(int initialByte, byte[] symbols) throws CborException {
        switch (AdditionalInformation.ofByte(initialByte)) {
            case DIRECT:
                return initialByte & 31;
            case ONE_BYTE:
                return symbols[0] & 0xFF; // a count of 128-255 must not read as a negative byte
            case TWO_BYTES:
                long twoByteValue = 0;
                twoByteValue |= (symbols[0] & 0xFF) << 8;
                twoByteValue |= (symbols[1] & 0xFF) << 0;
                return twoByteValue;
            case FOUR_BYTES:
                long fourByteValue = 0L;
                fourByteValue |= (long) (symbols[0] & 0xFF) << 24;
                fourByteValue |= (long) (symbols[1] & 0xFF) << 16;
                fourByteValue |= (long) (symbols[2] & 0xFF) << 8;
                fourByteValue |= (long) (symbols[3] & 0xFF) << 0;
                return fourByteValue;
            case EIGHT_BYTES:
                long eightByteValue = 0;
                eightByteValue |= (long) (symbols[0] & 0xFF) << 56;
                eightByteValue |= (long) (symbols[1] & 0xFF) << 48;
                eightByteValue |= (long) (symbols[2] & 0xFF) << 40;
                eightByteValue |= (long) (symbols[3] & 0xFF) << 32;
                eightByteValue |= (long) (symbols[4] & 0xFF) << 24;
                eightByteValue |= (long) (symbols[5] & 0xFF) << 16;
                eightByteValue |= (long) (symbols[6] & 0xFF) << 8;
                eightByteValue |= (long) (symbols[7] & 0xFF) << 0;
                return eightByteValue;
            case INDEFINITE:
                return INFINITY;
            case RESERVED:
            default:
                throw new CborException("Reserved additional information");
        }
    }
}
