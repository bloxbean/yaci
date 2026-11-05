package com.bloxbean.cardano.yaci.core.util;

import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.yaci.core.exception.CborRuntimeException;
import com.bloxbean.cardano.yaci.core.types.NonNegativeInterval;
import com.bloxbean.cardano.yaci.core.types.UnitInterval;
import lombok.NonNull;

import java.math.BigInteger;
import java.util.List;

public class CborSerializationUtil {

    /**
     * Covert a CBOR DataItem to BigInteger
     *
     * @param valueItem
     * @return
     */
    public static BigInteger toBigInteger(DataItem valueItem) {
        return com.bloxbean.cardano.client.common.cbor.CborSerializationUtil.getBigInteger(valueItem);
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
        return com.bloxbean.cardano.client.common.cbor.CborSerializationUtil.toHex(di);
    }

    public static byte[] toBytes(DataItem di) {
        return com.bloxbean.cardano.client.common.cbor.CborSerializationUtil.toBytes(di);
    }

    public static String toUnicodeString(DataItem di) {
        return com.bloxbean.cardano.client.common.cbor.CborSerializationUtil.toUnicodeString(di);
    }

    public static UnitInterval toUnitInterval(DataItem di) {
        RationalNumber rdi = (RationalNumber) di;
        return new UnitInterval(rdi.getNumerator().getValue(), rdi.getDenominator().getValue());
    }

    public static NonNegativeInterval toNonNegativeInterval(DataItem di) {
        RationalNumber rdi = (RationalNumber) di;
        return new NonNegativeInterval(rdi.getNumerator().getValue(), rdi.getDenominator().getValue());
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
        try {
            return com.bloxbean.cardano.client.common.cbor.CborSerializationUtil.serialize(values, canonical);
        } catch (CborException e) {
            throw new CborRuntimeException("Cbor serialization error", e);
        }
    }

    /**
     * Deserialize bytes to a DataItem. If multiple DataItem found at top level, return the first DataItem
     * @param bytes
     * @return DataItem
     */
    public static DataItem deserializeOne(@NonNull byte[] bytes) {
        return decodeAll(bytes).get(0);
    }

    /**
     * A span over bytes, for a reader that reads them in place of a decoded tree (ADR 0003 D8).
     *
     * @param bytes exactly one CBOR item
     * @return the span
     * @throws com.bloxbean.cardano.client.exception.CborRuntimeException if decoding the bytes would reject them
     */
    public static CborSpan span(@NonNull byte[] bytes) {
        CborSpan span = CborSpan.of(bytes);
        com.bloxbean.cardano.client.common.cbor.CborSerializationUtil.checkTags(span);
        return span;
    }

    /**
     * Deserialize bytes to list of DataItem.
     * @param bytes
     * @return DataItem
     */
    public static DataItem[] deserialize(@NonNull byte[] bytes) {
        return decodeAll(bytes).toArray(new DataItem[0]);
    }

    private static List<DataItem> decodeAll(byte[] bytes) {
        try {
            return com.bloxbean.cardano.client.common.cbor.CborSerializationUtil.deserializeAll(bytes);
        } catch (com.bloxbean.cardano.client.exception.CborRuntimeException e) {
            throw new CborRuntimeException("Cbor de-serialization error", e);
        }
    }

    // =====================================================================
    // CBOR Serialization Helpers (inverse of toInt/toLong/toUnitInterval etc.)
    // =====================================================================

    /**
     * Serialize a UnitInterval (or NonNegativeInterval) as CBOR tag 30 rational [numerator, denominator].
     * Inverse of {@link #toUnitInterval(DataItem)}.
     */
    public static DataItem serializeRational(UnitInterval ui) {
        Array arr = new Array();
        arr.add(new UnsignedInteger(ui.getNumerator()));
        arr.add(new UnsignedInteger(ui.getDenominator()));
        arr.setTag(30);
        return arr;
    }

    /** Create a CBOR UnsignedInteger from a long value. */
    public static UnsignedInteger cborUInt(long val) {
        return new UnsignedInteger(val);
    }

    /** Create a CBOR UnsignedInteger from a BigInteger value. */
    public static UnsignedInteger cborUInt(BigInteger val) {
        return new UnsignedInteger(val);
    }

    /** Put an int value into a CBOR Map with an int key. */
    public static void cborMapPutUInt(Map map, int key, int val) {
        map.put(cborUInt(key), cborUInt(val));
    }

    /** Put a BigInteger value into a CBOR Map with an int key. */
    public static void cborMapPutBigUInt(Map map, int key, BigInteger val) {
        map.put(cborUInt(key), new UnsignedInteger(val));
    }
}

