package com.bloxbean.cardano.yaci.core.util;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArrayCborCodecTest {
    @Test
    void preservesLibrarySemanticsForArraysTagsMapsAndScalars() throws Exception {
        String[] examples = {
                "80", "830182020380", "9f018202039fff04ff", "d90102820102",
                "a2019f0102ff028183030405", "82d81e820102d8268262656e626869",
                "841718181901001a00010000", "825f4201024103ff7f61616162ff", "818001"
        };
        for (String hex : examples) {
            byte[] bytes = HexUtil.decodeHexString(hex);
            List<DataItem> expected = CborDecoder.decode(bytes);
            List<DataItem> actual = ArrayCborDecoder.decode(bytes);
            assertThat(actual).as(hex).isEqualTo(expected);
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            new CborEncoder(encoded).nonCanonical().encode(expected);
            assertThat(CborSerializationUtil.serialize(actual.toArray(new DataItem[0]), false))
                    .as(hex).isEqualTo(encoded.toByteArray());
        }
    }

    @Test
    void handlesDeepDefiniteAndIndefiniteArraysWithoutRecursion() throws Exception {
        for (boolean indefinite : new boolean[]{false, true}) {
            String hex = (indefinite ? "9f" : "81").repeat(10000) + "00"
                    + (indefinite ? "ff".repeat(10000) : "");
            byte[] bytes = HexUtil.decodeHexString(hex);
            List<DataItem> decoded = ArrayCborDecoder.decode(bytes);
            assertThat(CborSerializationUtil.serialize(decoded.toArray(new DataItem[0]), false)).isEqualTo(bytes);
            DataItem leaf = decoded.get(0);
            for (int i = 0; i < 10000; i++) {
                leaf = ((Array) leaf).getDataItems().get(0);
            }
            assertThat(leaf).isEqualTo(new UnsignedInteger(0));
        }
    }

    @Test
    void rejectsTruncatedArraysAndLengths() {
        for (String hex : new String[]{"81", "9f01", "8181", "98", "9900", "81d90102"}) {
            assertThatThrownBy(() -> ArrayCborDecoder.decode(HexUtil.decodeHexString(hex)))
                    .as(hex).isInstanceOf(CborException.class);
        }
    }

    @Test
    void preservesStreamingPositionsAndIndefiniteArrayOption() throws Exception {
        ByteArrayInputStream input = new ByteArrayInputStream(HexUtil.decodeHexString("9f818001ff02"));
        ArrayCborDecoder decoder = new ArrayCborDecoder(input);
        decoder.setAutoDecodeInfinitiveArrays(false);
        Array header = (Array) decoder.decodeNext();
        assertThat(header.isChunked()).isTrue();
        assertThat(header.getDataItems()).isEmpty();
        assertThat(input.available()).isEqualTo(5);
        assertThat(decoder.decodeNext()).isInstanceOf(Array.class);
        assertThat(input.available()).isEqualTo(3);
        assertThat(decoder.decodeNext()).isEqualTo(new UnsignedInteger(1));
        assertThat(input.available()).isEqualTo(2);
    }

    @Test
    void allocationLimitsDoNotChangeDecodedValuesOrAcceptTruncatedHugeLengths() throws Exception {
        byte[] bytes = HexUtil.decodeHexString("9818" + "00".repeat(24));
        for (int limit : new int[]{-1, 0, 1, 16, 4096}) {
            ArrayCborDecoder decoder = new ArrayCborDecoder(new ByteArrayInputStream(bytes));
            decoder.setMaxPreallocationSize(limit);
            assertThat(decoder.decode()).isEqualTo(CborDecoder.decode(bytes));
            ArrayCborDecoder truncated = new ArrayCborDecoder(new ByteArrayInputStream(
                    HexUtil.decodeHexString("9b00000000ffffffff")));
            truncated.setMaxPreallocationSize(limit);
            assertThatThrownBy(truncated::decode).isInstanceOf(CborException.class);
        }
    }
}
