package com.bloxbean.cardano.yaci.core.model.serializers.util;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.AdditionalInformation;
import co.nstant.in.cbor.model.Special;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.util.Tuple;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

import static com.bloxbean.cardano.yaci.core.model.serializers.util.TransactionBodyExtractor.getLength;
import static com.bloxbean.cardano.yaci.core.model.serializers.util.TransactionBodyExtractor.getSymbolBytes;

public final class WitnessUtil {

    private WitnessUtil() {}

    /**
     * Get transaction witnesses of a block in raw bytes
     * @param blockBytes raw block bytes
     * @return a list of transaction witnesses in raw bytes
     * @throws CborException
     */
    public static List<byte[]> getWitnessRawData(byte[] blockBytes) throws CborException {
        ByteArrayInputStream stream = new ByteArrayInputStream(blockBytes);
        CborDecoder decoder = new CborDecoder(stream);

        stream.read();
        decoder.decodeNext();
        stream.read();
        decoder.decodeNext();
        decoder.decodeNext();

        // first element is witness
        var witnessLengthByte = stream.read();
        long witnessElementCount = getLength(witnessLengthByte,
                getSymbolBytes(blockBytes.length - stream.available(), blockBytes));

        List<byte[]> witnessList = new ArrayList<>();

        for (int i = 0; i < witnessElementCount; i++) {
            final var start = blockBytes.length - stream.available(); // start cutting position
            // find witness byte array length
            final var previous = stream.available();
            decoder.decodeNext();
            final var current = stream.available();
            final byte[] witness = new byte[previous - current];
            System.arraycopy(blockBytes, start, witness, 0, witness.length);
            witnessList.add(witness);
        }

        return witnessList;
    }

    /**
     * Get raw bytes of transaction witnesses fields
     *
     * @param witnessBytes transaction witnesses raw bytes
     * @return a map of transaction witnesses fields raw bytes with indexes
     * @throws CborException
     */
    public static java.util.Map<BigInteger, byte[]> getWitnessFields(byte[] witnessBytes)
            throws CborException {
        var witnessMap = new HashMap<BigInteger, byte[]>();

        ByteArrayInputStream stream = new ByteArrayInputStream(witnessBytes);
        CborDecoder decoder = new CborDecoder(stream);
        stream.read();

        while (stream.available() > 0) {
            UnsignedInteger key = (UnsignedInteger) decoder.decodeNext();
            final int datumStartFrom = witnessBytes.length - stream.available();
            int previousAvailable = stream.available();
            decoder.decodeNext();
            int currentAvailable = stream.available();

            final byte[] fieldBytes = new byte[previousAvailable - currentAvailable];
            System.arraycopy(witnessBytes, datumStartFrom, fieldBytes, 0, fieldBytes.length);
            witnessMap.put(key.getValue(), fieldBytes);
        }

        return witnessMap;
    }

    //Conway era
    public static List<Tuple<byte[], byte[]>> getRedeemerMapBytes(byte[] redeemerBytes)
            throws CborException {
        var redeemerList = new ArrayList<Tuple<byte[], byte[]>>();

        ByteArrayInputStream stream = new ByteArrayInputStream(redeemerBytes);
        CborDecoder decoder = new CborDecoder(stream);

        stream.read();

        while (stream.available() > 0) {
            int keyStartFrom = redeemerBytes.length - stream.available();
            int previousAvailable = stream.available();
            var keyDI = decoder.decodeNext();
            int currentAvailable = stream.available();
            final byte[] keyBytes = new byte[previousAvailable - currentAvailable];
            System.arraycopy(redeemerBytes, keyStartFrom, keyBytes, 0, keyBytes.length);

            int valueStartFrom = redeemerBytes.length - stream.available();
            previousAvailable = stream.available();
            var valueDI = decoder.decodeNext();
            currentAvailable = stream.available();
            final byte[] valueBytes = new byte[previousAvailable - currentAvailable];
            System.arraycopy(redeemerBytes, valueStartFrom, valueBytes, 0, valueBytes.length);

            redeemerList.add(new Tuple<>(keyBytes, valueBytes));
        }

        return redeemerList;
    }

    /**
     * Get CDDL array elements in bytes
     * @param bytes CDDL array bytes
     * @return a list of CDDL array elements in raw bytes
     * @throws CborException
     */
    public static List<byte[]> getArrayBytes(byte[] bytes) throws CborException {
        ByteArrayInputStream stream = new ByteArrayInputStream(bytes);
        final List<byte[]> dataItemBytes = new ArrayList<>();
        CborDecoder decoder = new CborDecoder(stream);
        final var arraySymbol = stream.read();
        final var dataLength = getLength(arraySymbol,
                getSymbolBytes(bytes.length - stream.available(), bytes));
        int skipBytes = skipBytes(arraySymbol);

        if (dataLength != TransactionBodyExtractor.INFINITY) {
            if (dataLength == BigInteger.ONE.intValue()) {
                dataItemBytes.add(getSymbolBytes((int) dataLength, bytes));
                return dataItemBytes;
            }

            while (skipBytes-- > 0) {
                stream.read();
            }
        }

        while (true) { // skip last BEAK symbol
            final int start = bytes.length - stream.available();
            final int previousAvailable = stream.available();

            final var dataItem = decoder.decodeNext();
            final int currentAvailable = stream.available();
            final byte[] dataItemElement = new byte[previousAvailable - currentAvailable];

            System.arraycopy(bytes, start, dataItemElement, 0, dataItemElement.length);

            if (Objects.isNull(dataItem) || dataItem.equals(Special.BREAK)) {
                break;
            }
            dataItemBytes.add(dataItemElement);
        }

        return dataItemBytes;
    }

    private static int skipBytes(int initialByte) throws CborException {
         switch (AdditionalInformation.ofByte(initialByte)) {
             case DIRECT:
                return 0;
             case ONE_BYTE:
                 return 1;
             case TWO_BYTES:
                 return  2;
             case FOUR_BYTES:
                 return 4;
             case EIGHT_BYTES:
                 return 8;
             case RESERVED:
                 throw new CborException("Reserved additional information");
             case INDEFINITE:
                 return -1;
             default:
                 throw new CborException("Invalid initialByte");
        }
    }

    /**
     * Get redeemer fields in raw bytes
     * @param redeemer redeemer object raw bytes
     * @return a list of redeemer object fields in raw bytes
     * @throws CborException
     */
    public static List<byte[]> getRedeemerFields(byte[] redeemer) throws CborException {
        var insideRedeemer = new ArrayList<byte[]>();

        ByteArrayInputStream stream = new ByteArrayInputStream(redeemer);
        CborDecoder decoder = new CborDecoder(stream);
        stream.read();

        while (stream.available() > 0) {
            final int fieldStartFrom = redeemer.length - stream.available();
            int previousAvailable = stream.available();
            decoder.decodeNext();
            int currentAvailable = stream.available();

            final byte[] fieldBytes = new byte[previousAvailable - currentAvailable];
            System.arraycopy(redeemer, fieldStartFrom, fieldBytes, 0, fieldBytes.length);
            insideRedeemer.add(fieldBytes);
        }

        return insideRedeemer;
    }
}
