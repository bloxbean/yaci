package com.bloxbean.cardano.yaci.core.util;

import co.nstant.in.cbor.CborBuilder;
import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.*;
import co.nstant.in.cbor.model.Number;
import com.bloxbean.cardano.yaci.core.exception.CborRuntimeException;
import lombok.NonNull;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

public class CborSerializationUtil {

    /**
     * Covert a CBOR DataItem to BigInteger
     *
     * @param valueItem
     * @return
     */
    public static BigInteger toBigInteger(DataItem valueItem) {
        BigInteger value = null;
        if (MajorType.UNSIGNED_INTEGER.equals(valueItem.getMajorType())
                || MajorType.NEGATIVE_INTEGER.equals(valueItem.getMajorType())) {
            value = ((Number) valueItem).getValue();
        } else if (MajorType.BYTE_STRING.equals(valueItem.getMajorType())) { //For BigNum. >  2 pow 64 Tag 2
            if (valueItem.getTag().getValue() == 2) { //positive
                value = new BigInteger(((ByteString) valueItem).getBytes());
            } else if (valueItem.getTag().getValue() == 3) { //Negative
                value = new BigInteger(((ByteString) valueItem).getBytes()).multiply(BigInteger.valueOf(-1));
            }
        }

        return value;
    }

    /**
     * Convert a CBOR DataItem to long
     * @param valueItem
     * @return
     */
    public static long toLong(DataItem valueItem) {
        return toBigInteger(valueItem).longValue();
    }

    /**
     * Convert a CBOR DataItem to int
     * @param valueItem
     * @return
     */
    public static int toInt(DataItem valueItem) {
        return toBigInteger(valueItem).intValue();
    }

    public static short toShort(DataItem valueItem) {
        return toBigInteger(valueItem).shortValue();
    }

    public static byte toByte(DataItem valueItem) {
        return toBigInteger(valueItem).byteValue();
    }

    public static String toHex(DataItem di) {
        return HexUtil.encodeHexString(((ByteString)di).getBytes());
    }

    public static byte[] toBytes(DataItem di) {
        return ((ByteString)di).getBytes();
    }

    public static String toUnicodeString(DataItem di) {
        return ((UnicodeString)di).getString();
    }

    //convert [1,50] to "1/50"
    public static String toRationalNumberStr(DataItem di) {
        RationalNumber rdi = (RationalNumber) di;
        return rdi.getNumerator() + "/" + rdi.getDenominator();
    }

    //convert [1, 50] to 0.02
    public static BigDecimal toRationalNumber(DataItem di) {
        RationalNumber rdi = (RationalNumber) di;
        Number numerator = rdi.getNumerator();
        Number denominator = rdi.getDenominator();

        try {
            return new BigDecimal(numerator.getValue()).divide(new BigDecimal(denominator.getValue()));
        } catch (ArithmeticException e) { //set scale and try again
            return new BigDecimal(numerator.getValue()).divide(new BigDecimal(denominator.getValue()), 10, RoundingMode.HALF_UP);
        }
    }

    /**
     * Serialize CBOR DataItem as byte[]
     *
     * @param value
     * @return
     */
    public static byte[] serialize(DataItem value) {
        return serialize(new DataItem[]{value}, true); //By default Canonical = true
    }

    /**
     * Serialize CBOR DataItem as byte[]
     *
     * @param value
     * @param canonical
     * @return
     */
    public static byte[] serialize(DataItem value, boolean canonical) {
        return serialize(new DataItem[]{value}, canonical);
    }

    /**
     * Serialize CBOR DataItems as byte[]
     *
     * @param values
     * @return
     */
    public static byte[] serialize(DataItem[] values) {
        return serialize(values, true); //By default Canonical = true
    }

    /**
     * Serialize CBOR DataItems as byte[]
     *
     * @param values
     * @param canonical
     * @return
     */
    public static byte[] serialize(DataItem[] values, boolean canonical) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CborBuilder cborBuilder = new CborBuilder();

        for (DataItem value : values) {
            cborBuilder.add(value);
        }

        try {
            if (canonical) {
                new CborEncoder(baos).encode(cborBuilder.build());
            } else {
                new CborEncoder(baos).nonCanonical().encode(cborBuilder.build());
            }
        } catch (CborException e) {
            throw new CborRuntimeException("Cbor serialization error", e);
        }

        byte[] encodedBytes = baos.toByteArray();

        return encodedBytes;

    }

    /**
     * Deserialize bytes to a DataItem. If multiple DataItem found at top level, return the first DataItem
     * @param bytes
     * @return DataItem
     */
    public static DataItem deserializeOne(@NonNull byte[] bytes) {
        try {
            return CborDecoder.decode(bytes).get(0);
        } catch (CborException e) {
            throw new CborRuntimeException("Cbor de-serialization error", e);
        }
    }

    /**
     * Deserialize bytes to list of DataItem.
     * @param bytes
     * @return DataItem
     */
    public static DataItem[] deserialize(@NonNull byte[] bytes) {
        try {
            return CborDecoder.decode(bytes).toArray(new DataItem[0]);
        } catch (CborException e) {
            throw new CborRuntimeException("Cbor de-serialization error", e);
        }
    }
}

