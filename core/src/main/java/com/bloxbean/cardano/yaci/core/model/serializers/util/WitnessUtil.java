package com.bloxbean.cardano.yaci.core.model.serializers.util;

import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.AdditionalInformation;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.MajorType;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.Tuple;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Extract original witness encodings without decoding or re-encoding their nested data. */
public final class WitnessUtil {
    private WitnessUtil() {}

    /**
     * Get transaction witnesses in source order. Shelley through Conway share the same positions:
     * [era, [header, transactionBodies, witnesses, auxiliaryData, ...]].
     * Only the first block is used, matching the block serializer's CBOR-sequence behavior.
     * @param blockBytes raw block bytes
     * @return original witness encodings
     * @throws CborException if the witness array or its prefix has invalid framing
     */
    public static List<byte[]> getWitnessRawData(byte[] blockBytes) throws CborException {
        return arrayBytes(CborSlice.arrayItem(blockBytes, 1, 2));
    }

    /**
     * Get witness fields by their unsigned integer keys, retaining exact value bytes.
     * @param witnessBytes one complete witness map
     * @return field encodings indexed by witness key
     * @throws CborException if the map framing or key type is invalid
     */
    public static Map<BigInteger, byte[]> getWitnessFields(byte[] witnessBytes) throws CborException {
        var fields = new HashMap<BigInteger, byte[]>();
        // witness = {0: vkeys, 1: nativeScripts, ..., 4: [datum, ...], 5: redeemers, ...}.
        List<CborSlice> entries = CborSlice.of(witnessBytes).items(MajorType.MAP);
        for (int i = 0; i < entries.size(); i += 2) {
            if (entries.get(i).type() != MajorType.UNSIGNED_INTEGER) {
                throw new CborException("Expected unsigned witness field key");
            }
            DataItem key = CborSerializationUtil.deserializeOne(entries.get(i).bytes());
            fields.put(((UnsignedInteger) key).getValue(), entries.get(i + 1).bytes());
        }
        return fields;
    }

    /**
     * Get Conway redeemer map entries in source order, excluding the map header and final BREAK.
     * @param redeemerBytes one complete redeemer map
     * @return original key/value encodings
     * @throws CborException if the map framing is invalid
     */
    public static List<Tuple<byte[], byte[]>> getRedeemerMapBytes(byte[] redeemerBytes) throws CborException {
        var result = new ArrayList<Tuple<byte[], byte[]>>();
        // Conway: {[tag, index]: [data, [memory, steps]], ...}; map items alternate key/value.
        List<CborSlice> entries = CborSlice.of(redeemerBytes).items(MajorType.MAP);
        for (int i = 0; i < entries.size(); i += 2) {
            result.add(new Tuple<>(entries.get(i).bytes(), entries.get(i + 1).bytes()));
        }
        return result;
    }

    /**
     * Get exact array elements, excluding container tags, length headers, and the closing BREAK.
     * Handles empty/singleton arrays and every definite-length width as well as indefinite arrays.
     * @param bytes one complete array (possibly tagged)
     * @return original item encodings
     * @throws CborException if framing is invalid, truncated, or contains trailing values
     */
    public static List<byte[]> getArrayBytes(byte[] bytes) throws CborException {
        return arrayBytes(CborSlice.of(bytes));
    }

    /**
     * Get an array-form redeemer's fields, or a Conway map key/value array's fields.
     * @param redeemer one complete array
     * @return original field encodings
     * @throws CborException if the array framing is invalid
     */
    public static List<byte[]> getRedeemerFields(byte[] redeemer) throws CborException {
        return getArrayBytes(redeemer);
    }

    /** Return the argument width used by the transaction-body and auxiliary-data extractors. */
    static int skipBytes(int initialByte) throws CborException {
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

    /** Copy immediate array children, retaining each child's tags and nested container encoding. */
    private static List<byte[]> arrayBytes(CborSlice array) throws CborException {
        List<byte[]> result = new ArrayList<>();
        for (CborSlice item : array.items(MajorType.ARRAY)) result.add(item.bytes());
        return result;
    }
}
