package com.bloxbean.cardano.yaci.core.model.serializers;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.bouncycastle.crypto.digests.Blake2bDigest;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Expected values for tests, taken from input bytes without the code under test or CCL: BouncyCastle's Blake2b, and a
 * minimal CBOR reader written from RFC 8949. An unsigned or negative integer reads as a {@link BigInteger}, a byte
 * string as its bytes (chunks joined), an array as a {@link List}, a map as a list of entries in encoded order, a tag
 * as a {@link Tagged}, true and false as booleans and null as {@link #NULL}.
 */
final class IndependentCbor {
    static final Object NULL = new Object();

    record Tagged(long tag, Object value) {
    }

    private final byte[] bytes;
    private int offset;

    private IndependentCbor(byte[] bytes) {
        this.bytes = bytes;
    }

    static Object read(byte[] bytes) {
        return new IndependentCbor(bytes).item();
    }

    static String blake2b256(byte[] bytes) {
        return HexUtil.encodeHexString(blake2b(bytes, 256));
    }

    static String blake2b224(byte[] bytes) {
        return HexUtil.encodeHexString(blake2b(bytes, 224));
    }

    private static byte[] blake2b(byte[] bytes, int bits) {
        Blake2bDigest digest = new Blake2bDigest(bits);
        digest.update(bytes, 0, bytes.length);
        byte[] hash = new byte[bits / 8];
        digest.doFinal(hash, 0);
        return hash;
    }

    // ---- navigation ----

    @SuppressWarnings("unchecked")
    static Object get(Object array, int index) {
        return ((List<Object>) untagged(array)).get(index);
    }

    @SuppressWarnings("unchecked")
    static List<Object> items(Object array) {
        return (List<Object>) untagged(array);
    }

    @SuppressWarnings("unchecked")
    static List<Map.Entry<Object, Object>> entries(Object map) {
        return (List<Map.Entry<Object, Object>>) untagged(map);
    }

    /** The value of an unsigned integer key of a map. */
    static Object field(Object map, long key) {
        for (Map.Entry<Object, Object> entry : entries(map))
            if (entry.getKey() instanceof BigInteger number && number.longValueExact() == key)
                return entry.getValue();
        throw new IllegalArgumentException("No key " + key);
    }

    static String hex(Object byteString) {
        return HexUtil.encodeHexString((byte[]) byteString);
    }

    static Object untagged(Object item) {
        return item instanceof Tagged tagged ? tagged.value() : item;
    }

    // ---- reading ----

    private Object item() {
        int initial = bytes[offset++] & 0xff;
        int major = initial >> 5;
        int info = initial & 0x1f;
        if (major == 7)
            return simple(info);
        boolean indefinite = info == 31;
        BigInteger argument = indefinite ? null : argument(info);
        switch (major) {
            case 0:
                return argument;
            case 1:
                return argument.negate().subtract(BigInteger.ONE);
            case 2:
            case 3:
                return indefinite ? chunks() : take(argument.intValueExact());
            case 4: {
                List<Object> items = new ArrayList<>();
                while (indefinite ? !atBreak() : items.size() < argument.intValueExact())
                    items.add(item());
                return items;
            }
            case 5: {
                List<Map.Entry<Object, Object>> entries = new ArrayList<>();
                while (indefinite ? !atBreak() : entries.size() < argument.intValueExact())
                    entries.add(new AbstractMap.SimpleEntry<>(item(), item()));
                return entries;
            }
            default:
                return new Tagged(argument.longValueExact(), item());
        }
    }

    private BigInteger argument(int info) {
        if (info < 24)
            return BigInteger.valueOf(info);
        int length = 1 << (info - 24);
        return new BigInteger(1, take(length));
    }

    private Object simple(int info) {
        return switch (info) {
            case 20 -> false;
            case 21 -> true;
            case 22 -> NULL;
            default -> throw new IllegalArgumentException("Unexpected simple value " + info);
        };
    }

    private byte[] chunks() {
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        while (!atBreak()) {
            int initial = bytes[offset++] & 0xff;
            joined.writeBytes(take(argument(initial & 0x1f).intValueExact()));
        }
        return joined.toByteArray();
    }

    private boolean atBreak() {
        if ((bytes[offset] & 0xff) != 0xff)
            return false;
        offset++;
        return true;
    }

    private byte[] take(int length) {
        byte[] taken = new byte[length];
        System.arraycopy(bytes, offset, taken, 0, length);
        offset += length;
        return taken;
    }
}
