package com.bloxbean.cardano.yaci.core.protocol.leios.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.SimpleValue;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosPoint;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosRawCbor;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosTxBitmap;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LeiosCborUtil {
    private static final BigInteger WORD64_MODULUS = BigInteger.ONE.shiftLeft(64);
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private static final BigInteger INT_MAX = BigInteger.valueOf(Integer.MAX_VALUE);

    private LeiosCborUtil() {
    }

    public static Array serializePointArray(LeiosPoint point) {
        Array array = new Array();
        array.add(new UnsignedInteger(point.getSlot()));
        array.add(new ByteString(point.getEbHash()));
        return array;
    }

    public static LeiosPoint deserializePointArray(DataItem dataItem) {
        List<DataItem> items = arrayItems(dataItem, "point");
        if (items.size() != 2) {
            throw new IllegalArgumentException("point must have 2 fields");
        }

        long slot = toLong((UnsignedInteger) items.get(0), "slot");
        byte[] ebHash = ((ByteString) items.get(1)).getBytes();
        return new LeiosPoint(slot, ebHash);
    }

    public static co.nstant.in.cbor.model.Map serializeTxBitmap(LeiosTxBitmap bitmap) {
        co.nstant.in.cbor.model.Map map = new co.nstant.in.cbor.model.Map();
        if (!bitmap.isEmpty()) {
            map.setChunked(true);
        }
        bitmap.getWindows().forEach((window, mask) ->
                map.put(new UnsignedInteger(window), unsignedLong(mask)));
        return map;
    }

    public static byte[] serializeTxBitmapBytes(LeiosTxBitmap bitmap) {
        // The CBOR library omits the break byte for empty chunked maps; write this field directly.
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(0xBF);
        bitmap.getWindows().forEach((window, mask) -> {
            writeBytes(baos, CborSerializationUtil.serialize(new UnsignedInteger(window), false));
            writeBytes(baos, CborSerializationUtil.serialize(unsignedLong(mask), false));
        });
        baos.write(0xFF);
        return baos.toByteArray();
    }

    public static LeiosTxBitmap deserializeTxBitmap(DataItem dataItem) {
        co.nstant.in.cbor.model.Map cborMap = (co.nstant.in.cbor.model.Map) dataItem;
        Map<Integer, Long> windows = new LinkedHashMap<>();
        for (DataItem key : cborMap.getKeys()) {
            if (key == SimpleValue.BREAK) {
                continue;
            }
            DataItem value = cborMap.get(key);
            int window = toWord16((UnsignedInteger) key);
            long mask = toWord64((UnsignedInteger) value);
            windows.put(window, mask);
        }

        return new LeiosTxBitmap(windows);
    }

    public static LeiosRawCbor toRawCbor(DataItem dataItem) {
        return LeiosRawCbor.of(CborSerializationUtil.serialize(dataItem, false));
    }

    public static DataItem fromRawCbor(LeiosRawCbor rawCbor) {
        DataItem[] dataItems = CborSerializationUtil.deserialize(rawCbor.getCbor());
        return dataItems[0];
    }

    public static byte[] validatedRawCborBytes(LeiosRawCbor rawCbor) {
        return rawCbor.getCbor();
    }

    public static Array serializeRawCborArray(List<LeiosRawCbor> rawItems) {
        Array array = new Array();
        rawItems.forEach(raw -> array.add(fromRawCbor(raw)));
        return array;
    }

    public static List<LeiosRawCbor> deserializeRawCborArrayItems(DataItem dataItem) {
        List<LeiosRawCbor> rawItems = new ArrayList<>();
        for (DataItem item : arrayItems(dataItem, "raw list")) {
            rawItems.add(toRawCbor(item));
        }
        return rawItems;
    }

    public static int readTag(DataItem dataItem) {
        List<DataItem> items = arrayItems(dataItem, "message");
        if (items.isEmpty()) {
            throw new IllegalArgumentException("message must contain a tag");
        }
        return toInt((UnsignedInteger) items.get(0), "message tag");
    }

    public static Array asArray(DataItem dataItem, String name) {
        if (!(dataItem instanceof Array array)) {
            throw new IllegalArgumentException(name + " must be a CBOR array");
        }
        return array;
    }

    public static List<DataItem> arrayItems(DataItem dataItem, String name) {
        List<DataItem> items = new ArrayList<>();
        for (DataItem item : asArray(dataItem, name).getDataItems()) {
            if (item != SimpleValue.BREAK) {
                items.add(item);
            }
        }
        return items;
    }

    public static boolean hasTag(ByteString byteString, long tagValue) {
        return byteString.getTag() != null && byteString.getTag().getValue() == tagValue;
    }

    private static UnsignedInteger unsignedLong(long value) {
        if (value >= 0) {
            return new UnsignedInteger(value);
        }
        return new UnsignedInteger(BigInteger.valueOf(value).add(WORD64_MODULUS));
    }

    private static int toWord16(UnsignedInteger value) {
        BigInteger bigint = value.getValue();
        if (bigint.signum() < 0 || bigint.compareTo(BigInteger.valueOf(LeiosTxBitmap.MAX_WINDOW_INDEX)) > 0) {
            throw new IllegalArgumentException("bitmap window must fit in word16");
        }
        return bigint.intValue();
    }

    private static long toWord64(UnsignedInteger value) {
        BigInteger bigint = value.getValue();
        if (bigint.signum() < 0 || bigint.bitLength() > 64) {
            throw new IllegalArgumentException("bitmap mask must fit in word64");
        }
        return bigint.longValue();
    }

    public static long toLong(UnsignedInteger value, String name) {
        BigInteger bigint = value.getValue();
        if (bigint.signum() < 0 || bigint.compareTo(LONG_MAX) > 0) {
            throw new IllegalArgumentException(name + " must fit in signed long");
        }
        return bigint.longValue();
    }

    public static int toInt(UnsignedInteger value, String name) {
        BigInteger bigint = value.getValue();
        if (bigint.signum() < 0 || bigint.compareTo(INT_MAX) > 0) {
            throw new IllegalArgumentException(name + " must fit in int");
        }
        return bigint.intValue();
    }

    private static void writeBytes(ByteArrayOutputStream baos, byte[] bytes) {
        baos.write(bytes, 0, bytes.length);
    }
}
