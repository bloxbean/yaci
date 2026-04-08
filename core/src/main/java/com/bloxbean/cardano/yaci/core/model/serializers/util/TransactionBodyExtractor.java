package com.bloxbean.cardano.yaci.core.model.serializers.util;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.AdditionalInformation;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Special;
import com.bloxbean.cardano.yaci.core.util.Tuple;
import lombok.SneakyThrows;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

//This is only for Shelley and post Shelley era blocks
public class TransactionBodyExtractor {

    public static final int INFINITY = -1;

    @SneakyThrows
    public static List<Tuple<DataItem, byte[]>> getTxBodiesFromBlock(byte[] blockBody) {
        // Handle tag-24 (wrapCBORinCBOR) format: [era, 24(h'<inner_bytes>')]
        // The inner bytes contain the actual block content array.
        // Commenting for now. This was identified during block production debugging. But not sure if it's really required.
        // blockBody = unwrapTag24IfNeeded(blockBody);

        List<Tuple<DataItem, byte[]>> txBodyTuples = new ArrayList<>();
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

        // Skip extra length bytes for definite-length arrays (>23 elements)
        int extraBytes = WitnessUtil.skipBytes(arrTxBodySize);
        if (extraBytes > 0) {
            bais.skip(extraBytes);
        }

        int start = blockBody.length - bais.available();
        for(int i = 0 ; i < length; i++){
            int previous = bais.available();
            DataItem dataItem = decoder.decodeNext();
            byte[] txBodyRaw = new byte[previous - bais.available()];
            System.arraycopy(blockBody,start,txBodyRaw,0,txBodyRaw.length);
            txBodyTuples.add(new Tuple<>(dataItem, txBodyRaw));
            start = blockBody.length - bais.available();
        }
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
                byte[] txBodyRaw = new byte[previous - bais.available()];
                System.arraycopy(blockBody, start, txBodyRaw, 0, txBodyRaw.length);
               txBodyTuples.add(new Tuple<>(dataItem, txBodyRaw));
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

    //TODO -- Remove this method if not required.
    /**
     * Detect tag-24 (wrapCBORinCBOR) format and convert to the old block format.
     * <p>
     * Tag-24 format: [era, 24(h'<inner_bytes>')]  where inner_bytes = [header, txBodies, ...]
     * Old format:    [era, [header, txBodies, ...]]
     * <p>
     * If tag-24 is detected, extracts the inner bytes (a CBOR array) and prepends a synthetic
     * 2-element outer array header + era integer so that existing byte-level parsers work unchanged.
     *
     * @param blockBytes raw block CBOR bytes
     * @return block bytes in the old format (with outer [era, inner_array]), or unchanged if already old format
     */
    @SneakyThrows
    static byte[] unwrapTag24IfNeeded(byte[] blockBytes) {
        if (blockBytes == null || blockBytes.length < 3) {
            return blockBytes;
        }

        // Parse outer array header to find position of second element
        ByteArrayInputStream bais = new ByteArrayInputStream(blockBytes);
        CborDecoder decoder = new CborDecoder(bais);

        // Skip outer array header byte(s)
        int firstByte = blockBytes[0] & 0xFF;
        int majorType = firstByte >> 5;
        if (majorType != 4) { // not an array
            return blockBytes;
        }

        // Read the outer array and era
        bais.read(); // outer array header
        decoder.decodeNext(); // era integer

        // Check if the next byte starts a tag (major type 6)
        int secondElementByte = blockBytes[blockBytes.length - bais.available()] & 0xFF;
        int secondMajorType = secondElementByte >> 5;
        if (secondMajorType != 6) {
            // Not a tag — already in old format
            return blockBytes;
        }

        // It's a tag-24 wrapped ByteString. Decode it to extract inner bytes.
        DataItem taggedItem = decoder.decodeNext();
        byte[] innerBytes;
        if (taggedItem instanceof ByteString) {
            innerBytes = ((ByteString) taggedItem).getBytes();
        } else {
            // Unexpected — return as-is
            return blockBytes;
        }

        // Reconstruct in old format: [era, <inner_array>]
        // We need to figure out how the era was encoded to preserve it exactly.
        // The outer array is always 2 elements: 0x82.
        // Era is a small unsigned integer (0-7), encoded as a single byte.
        // Reconstruct: 0x82 <era_byte> <inner_bytes>
        int eraPos = 1; // position right after outer array header byte
        byte eraByte = blockBytes[eraPos]; // the era integer byte (e.g. 0x06 for Conway)
        byte[] result = new byte[2 + innerBytes.length];
        result[0] = (byte) 0x82; // 2-element array
        result[1] = eraByte;
        System.arraycopy(innerBytes, 0, result, 2, innerBytes.length);
        return result;
    }
}
