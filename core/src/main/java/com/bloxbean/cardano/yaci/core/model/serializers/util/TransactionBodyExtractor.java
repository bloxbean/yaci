package com.bloxbean.cardano.yaci.core.model.serializers.util;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.AdditionalInformation;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Special;
import com.bloxbean.cardano.client.util.Triple;
import com.bloxbean.cardano.yaci.core.util.Tuple;
import lombok.SneakyThrows;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

//This is only for Shelley and post Shelley era blocks
public class TransactionBodyExtractor {

    public static final int INFINITY = -1;

    /**
     * Extracting TxBodies from raw block body.
     * @param blockBody raw block body
     * @return Triple: Deserialized Dataitem, Raw Bytes and Size
     */
    @SneakyThrows
    public static List<Triple<DataItem, byte[], Integer>> getTxBodiesFromBlock(byte[] blockBody) {
        List<Triple<DataItem, byte[], Integer>> txBodyTuples = new ArrayList<>();
        ByteArrayInputStream bais = new ByteArrayInputStream(blockBody);
        CborDecoder decoder = new CborDecoder(bais);

        //era and body
        bais.read();
        decoder.decodeNext();
        //body
        bais.read();
        decoder.decodeNext(); // skip header

        //tx bodies
        int arrTxBodySize = bais.read();

        long length = getLength(arrTxBodySize,getSymbolBytes(blockBody.length - bais.available(),blockBody));
        int start = blockBody.length - bais.available();
        if(AdditionalInformation.INDEFINITE.equals(AdditionalInformation.ofByte(arrTxBodySize))) {
            for (;;) {
                int previous = bais.available();
                var dataItem = decoder.decodeNext();
                if (dataItem == null) {
                    throw new CborException("Unexpected end of stream");
                }
                if (Special.BREAK.equals(dataItem)) {
                    break;
                }
                int txSize = previous - bais.available();
                byte[] txBodyRaw = new byte[previous - bais.available()];
                System.arraycopy(blockBody, start, txBodyRaw, 0, txBodyRaw.length);
                txBodyTuples.add(new Triple<>(dataItem, txBodyRaw, txSize));
                start = blockBody.length - bais.available();
            }
        } else {
            for (int i = 0; i < length; i++) {
                int previous = bais.available();
                DataItem dataItem = decoder.decodeNext();
                int txSize = previous - bais.available();
                byte[] txBodyRaw = new byte[txSize];
                System.arraycopy(blockBody, start, txBodyRaw, 0, txBodyRaw.length);
                txBodyTuples.add(new Triple<>(dataItem, txBodyRaw, txSize));
                start = blockBody.length - bais.available();
            }
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
                return symbols[0];
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
