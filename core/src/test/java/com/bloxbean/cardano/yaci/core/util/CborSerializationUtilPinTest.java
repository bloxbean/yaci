package com.bloxbean.cardano.yaci.core.util;

import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.NegativeInteger;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.exception.CborRuntimeException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pins what {@link CborSerializationUtil} accepts and writes, on top of CCL's decoder and encoder. */
class CborSerializationUtilPinTest {

    /**
     * Not well-formed under RFC 8949 section 5.3, and accepted before this decoder moved to CCL: BREAK where none can
     * be, a tag on BREAK, a two-byte simple value below 32, a chunk inside a chunk, and an indefinite argument on an
     * integer or tag. cbor-java tagged its one shared BREAK object for the rest of the JVM on {@code d879ff}.
     */
    @Test
    void nonWellFormedInputIsRejected() {
        for (String hex : new String[]{"ff", "81ff", "a100ff", "a0ff", "00ff", "d879ff", "f800", "f81f", "8181f800",
                "5f5f40ffff", "7f7f60ffff", "1f", "3f", "df00"}) {
            assertThatThrownBy(() -> CborSerializationUtil.deserialize(HexUtil.decodeHexString(hex)))
                    .as(hex).isInstanceOf(CborRuntimeException.class);
            assertThatThrownBy(() -> CborSerializationUtil.deserializeOne(HexUtil.decodeHexString(hex)))
                    .as(hex).isInstanceOf(CborRuntimeException.class);
        }
    }

    @Test
    void rejectedInputThrowsYacisOwnException() {
        for (String hex : new String[]{"81", "9f01", "98", "430001", "1c", "fc", "5f6100ff", "bf00ff", "d81e820100",
                "0081"}) {
            assertThatThrownBy(() -> CborSerializationUtil.deserialize(HexUtil.decodeHexString(hex)))
                    .as(hex).isInstanceOf(CborRuntimeException.class);
            assertThatThrownBy(() -> CborSerializationUtil.deserializeOne(HexUtil.decodeHexString(hex)))
                    .as(hex).isInstanceOf(CborRuntimeException.class);
        }
    }

    @Test
    void emptyInputGivesNoItems() {
        assertThat(CborSerializationUtil.deserialize(new byte[0])).isEmpty();
        assertThatThrownBy(() -> CborSerializationUtil.deserializeOne(new byte[0]))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void deserializeOneReturnsTheFirstOfSeveralValues() {
        byte[] bytes = HexUtil.decodeHexString("0102");
        assertThat(CborSerializationUtil.deserialize(bytes)).hasSize(2);
        assertThat(CborSerializationUtil.deserializeOne(bytes)).isEqualTo(new UnsignedInteger(1));
    }

    /** Canonical order is CCL's: unsigned bytes whatever the length. cbor-java wrote shorter first, then signed. */
    @Test
    void canonicalMapOrderComparesUnsignedBytes() {
        assertThat(canonical(new NegativeInteger(-1), new UnsignedInteger(32))).isEqualTo("a2" + "182001" + "2000");
        assertThat(canonical(new UnsignedInteger(32), new UnsignedInteger(128))).isEqualTo("a2" + "182000" + "188001");
        assertThat(canonical(new ByteString(new byte[]{0x7f}), new ByteString(new byte[]{(byte) 0x80})))
                .isEqualTo("a2" + "417f00" + "418001");
    }

    @Test
    void nonCanonicalMapKeepsInsertionOrder() {
        Map map = new Map().put(new UnsignedInteger(2), new UnsignedInteger(0)).put(new UnsignedInteger(1),
                new UnsignedInteger(1));
        assertThat(HexUtil.encodeHexString(CborSerializationUtil.serialize(map, false))).isEqualTo("a202000101");
        assertThat(HexUtil.encodeHexString(CborSerializationUtil.serialize(map))).isEqualTo("a201010200");
    }

    /** Maps the first key to 0 and the second to 1, so the output shows the order they were written in. */
    private static String canonical(DataItem first, DataItem second) {
        Map map = new Map().put(first, new UnsignedInteger(0)).put(second, new UnsignedInteger(1));
        return HexUtil.encodeHexString(CborSerializationUtil.serialize(map));
    }
}
