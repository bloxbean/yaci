package com.bloxbean.cardano.yaci.core.util;

import co.nstant.in.cbor.model.DataItem;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Scalar reads go through CCL's, which reads a bignum as RFC 8949 section 3.4.3 defines it. */
class CborSerializationUtilScalarTest {
    private static final BigInteger TWO_64 = BigInteger.ONE.shiftLeft(64);

    @Test
    void bignumContentIsAnUnsignedMagnitude() {
        // The first byte's high bit made these negative when the content was read as two's complement.
        assertThat(CborSerializationUtil.toBigInteger(item("c249" + "ff".repeat(9))))
                .isEqualTo(BigInteger.ONE.shiftLeft(72).subtract(BigInteger.ONE));
        assertThat(CborSerializationUtil.toBigInteger(item("c2488000000000000000")))
                .isEqualTo(BigInteger.ONE.shiftLeft(63));
    }

    @Test
    void negativeBignumIsMinusOneMinusItsContent() {
        // Tag 3 with content n is -1 - n; it was read as -n.
        assertThat(CborSerializationUtil.toBigInteger(item("c349010000000000000000")))
                .isEqualTo(BigInteger.ONE.negate().subtract(TWO_64));
        assertThat(CborSerializationUtil.toBigInteger(item("c34100"))).isEqualTo(BigInteger.ONE.negate());
    }

    @Test
    void integersAndChunkedBignumsAreRead() {
        assertThat(CborSerializationUtil.toBigInteger(item("1bffffffffffffffff")))
                .isEqualTo(TWO_64.subtract(BigInteger.ONE));
        assertThat(CborSerializationUtil.toBigInteger(item("3bffffffffffffffff"))).isEqualTo(TWO_64.negate());
        assertThat(CborSerializationUtil.toBigInteger(item("c25f4101" + "4800000000000000" + "00ff")))
                .isEqualTo(TWO_64);
    }

    @Test
    void aBignumWithAnotherTagIsNull() {
        assertThat(CborSerializationUtil.toBigInteger(item("c44101"))).isNull();
    }

    /** Narrower reads keep wrapping, as they do today. */
    @Test
    void narrowerReadsWrap() {
        assertThat(CborSerializationUtil.toLong(item("1bffffffffffffffff"))).isEqualTo(-1L);
        assertThat(CborSerializationUtil.toInt(item("1a80000000"))).isEqualTo(Integer.MIN_VALUE);
        assertThat(CborSerializationUtil.toShort(item("1a00010001"))).isEqualTo((short) 1);
        assertThat(CborSerializationUtil.toByte(item("190101"))).isEqualTo((byte) 1);
    }

    @Test
    void stringsAreRead() {
        assertThat(CborSerializationUtil.toHex(item("4201ff"))).isEqualTo("01ff");
        assertThat(CborSerializationUtil.toBytes(item("4201ff"))).containsExactly(1, 0xff);
        assertThat(CborSerializationUtil.toUnicodeString(item("6161"))).isEqualTo("a");
    }

    private static DataItem item(String hex) {
        return CborSerializationUtil.deserializeOne(HexUtil.decodeHexString(hex));
    }
}
