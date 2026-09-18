package com.bloxbean.cardano.yaci.core.model.serializers.util;

import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.MajorType;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.core.common.EraUtil;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CborSliceTest {
    @Test
    void eraPrefixSupportsEveryEraAndCborHeaderWidthWithoutReadingTheBlock() throws Exception {
        for (int era = 0; era <= 7; era++) {
            for (String array : new String[]{"82", "9802", "990002", "9a00000002", "9b0000000000000002", "9f"}) {
                for (String value : new String[]{"0" + era, "180" + era, "19000" + era,
                        "1a0000000" + era, "1b000000000000000" + era}) {
                    // The deliberately malformed body must not be visited merely to read the era.
                    assertThat(EraUtil.getEraValue(HexUtil.decodeHexString(array + value + "ff")))
                            .isEqualTo(era);
                }
            }
        }
        byte[] envelope = HexUtil.decodeHexString("8207828101ff");
        assertThat(HexUtil.encodeHexString(CborSlice.arrayItem(envelope, 1, 0).bytes())).isEqualTo("8101");
        for (String invalid : new String[]{"", "80", "9fff", "a000", "8219", "8220", "821bffffffffffffffff"}) {
            assertThatThrownBy(() -> EraUtil.getEraValue(HexUtil.decodeHexString(invalid)))
                    .as(invalid).isInstanceOf(Exception.class);
        }
    }

    @Test
    void firstValueAllowsAValidSequenceButStillRejectsMalformedTrailingData() throws Exception {
        assertThat(HexUtil.encodeHexString(CborSlice.first(HexUtil.decodeHexString("81008101")).bytes()))
                .isEqualTo("8100");
        assertThatThrownBy(() -> CborSlice.first(HexUtil.decodeHexString("810081")))
                .isInstanceOf(CborException.class);
    }

    @Test
    void scansDeepContainersAndKeysWithoutConstructingDataItems() throws Exception {
        for (String hex : new String[]{"a100".repeat(10000) + "00", "d87981".repeat(10000) + "00",
                "bf00".repeat(10000) + "00" + "ff".repeat(10000),
                "d8799f".repeat(10000) + "00" + "ff".repeat(10000),
                "a1" + "81".repeat(10000) + "0001", "d90102".repeat(10000) + "00"}) {
            byte[] bytes = HexUtil.decodeHexString(hex);
            CborSlice source = CborSlice.of(bytes);
            assertThat(source.bytes()).isEqualTo(bytes);
        }
    }

    @Test
    void retainsTagsNonMinimalArgumentsAndPayloadBreakBytes() throws Exception {
        String hex = "d901029f18005f41ff4100ff7f61616162fffa3f800000ff";
        CborSlice source = CborSlice.of(HexUtil.decodeHexString(hex));
        assertThat(source.type()).isEqualTo(MajorType.ARRAY);
        assertThat(source.tag()).isEqualTo(258);
        var items = source.items(MajorType.ARRAY);
        assertThat(items).hasSize(4);
        assertThat(HexUtil.encodeHexString(items.get(0).bytes())).isEqualTo("1800");
        assertThat(HexUtil.encodeHexString(items.get(1).bytes())).isEqualTo("5f41ff4100ff");
        assertThat(HexUtil.encodeHexString(source.replacing(Collections.singletonList(items.get(0)), (byte) 1)))
                .isEqualTo(hex.replace("9f1800", "9f01"));
        CborSlice map = CborSlice.of(HexUtil.decodeHexString("bf01020304ff"));
        assertThat(map.items(MajorType.MAP)).hasSize(4);
        for (String scalar : new String[]{"1bffffffffffffffff", "3bffffffffffffffff", "f93c00",
                "fb3ff0000000000000", "d81e820102", "d8268262656e626869"}) {
            assertThat(CborSlice.of(HexUtil.decodeHexString(scalar)).bytes())
                    .isEqualTo(HexUtil.decodeHexString(scalar));
        }
        CborSlice extended = CborSlice.of(HexUtil.decodeHexString("d901029818" + "00".repeat(24)));
        assertThat(extended.items(MajorType.ARRAY)).hasSize(24);
    }

    @Test
    void rejectsTruncatedMalformedAndOverlappingRanges() throws Exception {
        for (String hex : new String[]{"", "00ff", "ff", "81", "a100", "bf00ff", "9f00", "d879",
                "9bffffffffffffffff", "5f6100ff", "7f5f40ffff", "8101ff", "1e", "1900", "430001"}) {
            assertThatThrownBy(() -> CborSlice.of(HexUtil.decodeHexString(hex)))
                    .as(hex).isInstanceOf(CborException.class);
        }
        CborSlice source = CborSlice.of(HexUtil.decodeHexString("820001"));
        var items = source.items(MajorType.ARRAY);
        assertThatThrownBy(() -> source.replacing(Arrays.asList(items.get(1), items.get(0)), (byte) 0, (byte) 0))
                .isInstanceOf(CborException.class);
        assertThatThrownBy(() -> source.items(MajorType.MAP)).isInstanceOf(CborException.class);
    }
}
