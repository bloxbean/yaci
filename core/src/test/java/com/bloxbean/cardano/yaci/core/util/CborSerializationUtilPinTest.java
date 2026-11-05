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

/**
 * Pins what {@link CborSerializationUtil} accepts and writes today. Delegating it to CCL (ADR 0002 D8) changes the two
 * tests marked below, so that move needs the maintainer's decision.
 */
class CborSerializationUtilPinTest {

    /**
     * Not well-formed under RFC 8949 section 5.3, and accepted today: BREAK where none can be, a two-byte simple value
     * below 32, a chunk inside a chunk, and an indefinite argument on an integer or tag. CCL's decoder rejects them all.
     * Never add a tag on BREAK here ({@code d879ff}): cbor-java tags its shared BREAK object for the rest of the JVM.
     */
    @Test
    void nonWellFormedInputThatDelegatingToCclWouldReject() {
        for (String hex : new String[]{"ff", "81ff", "a100ff", "a0ff", "00ff", "f800", "f81f", "8181f800",
                "5f5f40ffff", "7f7f60ffff", "1f", "3f", "df00"}) {
            assertThat(CborSerializationUtil.deserialize(HexUtil.decodeHexString(hex))).as(hex).isNotEmpty();
            assertThat(CborSerializationUtil.deserializeOne(HexUtil.decodeHexString(hex))).as(hex).isNotNull();
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

    /**
     * Canonical order is cbor-java's: shorter keys first, then bytes compared as signed. CCL's encoder compares unsigned
     * bytes whatever the length, and so writes all three of these maps the other way round.
     */
    @Test
    void canonicalMapOrderThatDelegatingToCclWouldChange() {
        assertThat(canonical(new NegativeInteger(-1), new UnsignedInteger(32))).isEqualTo("a2" + "2000" + "182001");
        assertThat(canonical(new UnsignedInteger(32), new UnsignedInteger(128))).isEqualTo("a2" + "188001" + "182000");
        assertThat(canonical(new ByteString(new byte[]{0x7f}), new ByteString(new byte[]{(byte) 0x80})))
                .isEqualTo("a2" + "418001" + "417f00");
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
