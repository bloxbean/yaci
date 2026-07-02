package com.bloxbean.cardano.yaci.core.protocol.leios;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.SimpleValue;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.leios.serializers.LeiosCborUtil;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LeiosCborUtilTest {

    @Test
    void serializesAndDeserializesPoint() {
        byte[] hash = hash(7);
        LeiosPoint point = new LeiosPoint(42, hash);

        DataItem pointDi = LeiosCborUtil.serializePointArray(point);
        LeiosPoint deserialized = LeiosCborUtil.deserializePointArray(pointDi);

        assertEquals(42, deserialized.getSlot());
        assertArrayEquals(hash, deserialized.getEbHash());
    }

    @Test
    void deserializesPointWithTrailingBreakItem() {
        byte[] hash = hash(8);
        Array point = new Array();
        point.add(new UnsignedInteger(43));
        point.add(new ByteString(hash));
        point.add(SimpleValue.BREAK);

        LeiosPoint deserialized = LeiosCborUtil.deserializePointArray(point);

        assertEquals(43, deserialized.getSlot());
        assertArrayEquals(hash, deserialized.getEbHash());
    }

    @Test
    void rejectsPointSlotOutsideSignedLongRange() {
        Array point = new Array();
        point.add(new UnsignedInteger(BigInteger.ONE.shiftLeft(63)));
        point.add(new co.nstant.in.cbor.model.ByteString(hash(0)));

        assertThrows(IllegalArgumentException.class, () -> LeiosCborUtil.deserializePointArray(point));
    }

    @Test
    void serializesBitmapAsIndefiniteMap() {
        LeiosTxBitmap bitmap = LeiosTxBitmap.fromIndices(0);

        byte[] bytes = LeiosCborUtil.serializeTxBitmapBytes(bitmap);

        assertEquals((byte) 0xbf, bytes[0]);
        assertEquals((byte) 0xff, bytes[bytes.length - 1]);
        assertEquals(bitmap, LeiosCborUtil.deserializeTxBitmap(CborSerializationUtil.deserializeOne(bytes)));
    }

    @Test
    void serializesEmptyBitmapAsIndefiniteMap() {
        byte[] bytes = LeiosCborUtil.serializeTxBitmapBytes(LeiosTxBitmap.empty());

        assertArrayEquals(new byte[]{(byte) 0xbf, (byte) 0xff}, bytes);
        assertEquals(LeiosTxBitmap.empty(),
                LeiosCborUtil.deserializeTxBitmap(CborSerializationUtil.deserializeOne(bytes)));
    }

    @Test
    void dataItemEmptyBitmapUsesDefiniteMapToAvoidInvalidLibraryEncoding() {
        byte[] bytes = CborSerializationUtil.serialize(
                LeiosCborUtil.serializeTxBitmap(LeiosTxBitmap.empty()), false);

        assertArrayEquals(new byte[]{(byte) 0xa0}, bytes);
        assertEquals(LeiosTxBitmap.empty(),
                LeiosCborUtil.deserializeTxBitmap(CborSerializationUtil.deserializeOne(bytes)));
    }

    @Test
    void decodesDefiniteBitmapMap() {
        co.nstant.in.cbor.model.Map map = new co.nstant.in.cbor.model.Map();
        map.put(new UnsignedInteger(0), new UnsignedInteger(1));
        map.put(new UnsignedInteger(2), new UnsignedInteger(3));

        LeiosTxBitmap bitmap = LeiosCborUtil.deserializeTxBitmap(map);

        assertEquals(Map.of(0, 1L, 2, 3L), bitmap.getWindows());
    }

    @Test
    void rejectsBitmapWindowOutsideWord16() {
        co.nstant.in.cbor.model.Map map = new co.nstant.in.cbor.model.Map();
        map.put(new UnsignedInteger(BigInteger.ONE.shiftLeft(32)), new UnsignedInteger(1));

        assertThrows(IllegalArgumentException.class, () -> LeiosCborUtil.deserializeTxBitmap(map));
    }

    @Test
    void rejectsBitmapMaskOutsideWord64() {
        co.nstant.in.cbor.model.Map map = new co.nstant.in.cbor.model.Map();
        map.put(new UnsignedInteger(0), new UnsignedInteger(BigInteger.ONE.shiftLeft(64)));

        assertThrows(IllegalArgumentException.class, () -> LeiosCborUtil.deserializeTxBitmap(map));
    }

    @Test
    void wrapsOpaqueCborWithoutDomainDecoding() {
        Array rawArray = new Array();
        rawArray.add(new UnsignedInteger(1));
        rawArray.add(new UnsignedInteger(2));

        LeiosRawCbor raw = LeiosCborUtil.toRawCbor(rawArray);
        DataItem dataItem = LeiosCborUtil.fromRawCbor(raw);

        assertEquals(1, LeiosCborUtil.readTag(dataItem));
    }

    @Test
    void rejectsMessageTagOutsideIntRange() {
        Array message = new Array();
        message.add(new UnsignedInteger(BigInteger.ONE.shiftLeft(32)));

        assertThrows(IllegalArgumentException.class, () -> LeiosCborUtil.readTag(message));
    }

    @Test
    void readsMessageTagWithTrailingBreakItem() {
        Array message = new Array();
        message.add(new UnsignedInteger(4));
        message.add(SimpleValue.BREAK);

        assertEquals(4, LeiosCborUtil.readTag(message));
    }

    @Test
    void rejectsRawCborSequencesWithMultipleTopLevelValues() {
        byte[] cborSequence = new byte[]{0x01, 0x02};

        assertThrows(IllegalArgumentException.class, () -> LeiosCborUtil.fromRawCbor(LeiosRawCbor.of(cborSequence)));
    }

    private byte[] hash(int seed) {
        byte[] hash = new byte[LeiosPoint.EB_HASH_LENGTH];
        for (int i = 0; i < hash.length; i++) {
            hash[i] = (byte) (seed + i);
        }
        return hash;
    }
}
