package com.bloxbean.cardano.yaci.core.model.serializers.util;

import co.nstant.in.cbor.CborException;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Byte-boundary cases shared by datum arrays, redeemer arrays/maps, and witness maps. */
class WitnessUtilBoundaryTest {
    private static final String[] TAGS = {"", "c0", "d818", "d90102", "da00000102",
            "db0000000000000102", "d90102d818"};
    // Include nested BREAKs, payload 0xff, non-minimal integers, maps, and constructor tags.
    private static final String[] ITEMS = {"1800", "d8799f01ff", "9f8102bf0304ffff",
            "5f41ff4100ff", "bf019f02ffff", "7f61616162ff", "fb3ff0000000000000"};

    /** Check exact child bytes across tag widths, length widths, and empty/singleton/extended arrays. */
    @TestFactory
    Stream<DynamicTest> arraysRetainExactItemsForEveryTagHeaderWidthAndBoundaryCount() {
        List<DynamicTest> tests = new ArrayList<>();
        for (int count : new int[]{0, 1, 2, 23, 24, 30, 255, 256}) {
            for (int width : new int[]{0, 1, 2, 4, 8, -1}) {
                if (width == 0 && count > 23 || width == 1 && count > 255) continue;
                for (String tag : TAGS) {
                    tests.add(DynamicTest.dynamicTest("array count=" + count + " width=" + width + " tag=" + tag,
                            () -> {
                                List<byte[]> expected = new ArrayList<>();
                                StringBuilder payload = new StringBuilder();
                                for (int i = 0; i < count; i++) {
                                    String item = ITEMS[i % ITEMS.length];
                                    payload.append(item);
                                    expected.add(hex(item));
                                }
                                byte[] bytes = hex(tag + header(4, count, width) + payload + end(width));
                                assertThat(WitnessUtil.getArrayBytes(bytes)).containsExactlyElementsOf(expected);
                                assertThat(WitnessUtil.getRedeemerFields(bytes)).containsExactlyElementsOf(expected);
                            }));
                }
            }
        }
        return tests.stream();
    }

    /** Check source-order map pairs and unsigned witness keys without assuming a one-byte header. */
    @TestFactory
    Stream<DynamicTest> mapsRetainExactPairsForEveryHeaderWidthAndBoundaryCount() {
        List<DynamicTest> tests = new ArrayList<>();
        for (int count : new int[]{0, 1, 2, 23, 24, 30, 255, 256}) {
            for (int width : new int[]{0, 1, 2, 4, 8, -1}) {
                if (width == 0 && count > 23 || width == 1 && count > 255) continue;
                for (String tag : TAGS) {
                    tests.add(DynamicTest.dynamicTest("map count=" + count + " width=" + width + " tag=" + tag,
                            () -> {
                                StringBuilder redeemers = new StringBuilder();
                                StringBuilder witness = new StringBuilder();
                                for (int i = 0; i < count; i++) {
                                    String key = String.format("19%04x", i);
                                    String data = ITEMS[i % ITEMS.length];
                                    redeemers.append("8200").append(key).append("82").append(data).append("820a14");
                                    witness.append(key).append(data);
                                }
                                var entries = WitnessUtil.getRedeemerMapBytes(
                                        hex(tag + header(5, count, width) + redeemers + end(width)));
                                var fields = WitnessUtil.getWitnessFields(
                                        hex(tag + header(5, count, width) + witness + end(width)));
                                assertThat(entries).hasSize(count);
                                assertThat(fields).hasSize(count);
                                for (int i = 0; i < count; i++) {
                                    String key = String.format("19%04x", i);
                                    String data = ITEMS[i % ITEMS.length];
                                    assertThat(entries.get(i)._1).isEqualTo(hex("8200" + key));
                                    assertThat(entries.get(i)._2).isEqualTo(hex("82" + data + "820a14"));
                                    assertThat(fields.get(BigInteger.valueOf(i))).isEqualTo(hex(data));
                                }
                            }));
                }
            }
        }
        return tests.stream();
    }

    /** Exercise [era, [header, bodies, witnesses, aux, ...]] with each supported container header. */
    @Test
    void blockWitnessExtractionHandlesAllEraLayoutsAndContainerWidths() throws Exception {
        for (int era = 2; era <= 7; era++) {
            for (int width : new int[]{0, 1, 2, 4, 8, -1}) {
                String witnesses = "d90102" + header(4, 2, width) + "a004" + end(width);
                String body = header(4, era < 5 ? 4 : 5, width) + "8080" + witnesses + "a0"
                        + (era < 5 ? "" : "80") + end(width);
                byte[] bytes = hex(header(4, 2, width) + "0" + era + body + end(width));
                assertThat(WitnessUtil.getWitnessRawData(bytes)).containsExactly(hex("a0"), hex("04"));
            }
        }
    }

    /** Verify nested data is sliced without constructing recursive CBOR objects. */
    @Test
    void deepDataIsSlicedWithoutRecursiveDecoding() throws Exception {
        for (String data : new String[]{"81".repeat(10000) + "00", "a100".repeat(10000) + "00",
                "d8799f".repeat(10000) + "00" + "ff".repeat(10000)}) {
            assertThat(WitnessUtil.getArrayBytes(hex("d9010281" + data))).containsExactly(hex(data));
            assertThat(WitnessUtil.getWitnessFields(hex("bf0481" + data + "ff"))
                    .get(BigInteger.valueOf(4))).isEqualTo(hex("81" + data));
            assertThat(WitnessUtil.getRedeemerMapBytes(hex("bf82000082" + data + "820102ff"))
                    .get(0)._2).isEqualTo(hex("82" + data + "820102"));
        }
    }

    /** Reject truncation, trailing values, reserved lengths, wrong types, and misplaced BREAK markers. */
    @TestFactory
    Stream<DynamicTest> invalidFramingIsRejectedWithCborExceptions() {
        List<DynamicTest> tests = new ArrayList<>();
        for (String value : new String[]{"", "ff", "00", "80ff", "810001", "81", "8200", "9f00",
                "9f00ffff", "81ff", "9c", "9d", "9e", "98", "9900", "9a000000", "9b00000000000000",
                "9bffffffffffffffff", "d9", "d901", "d90102", "df80", "8182430000",
                "815f6100ff", "819f00", "d90102981e00"}) {
            tests.add(DynamicTest.dynamicTest("invalid array " + value, () -> {
                assertThatThrownBy(() -> WitnessUtil.getArrayBytes(hex(value))).isInstanceOf(CborException.class);
                assertThatThrownBy(() -> WitnessUtil.getRedeemerFields(hex(value))).isInstanceOf(CborException.class);
            }));
        }
        for (String value : new String[]{"", "ff", "80", "a0ff", "a000", "a1", "a100", "bf00ff",
                "bf0000", "a1ff00", "b8", "b900", "ba000000", "bb00000000000000",
                "bbffffffffffffffff", "bc", "bd", "be", "d90102", "a10081", "bf0081ffff"}) {
            tests.add(DynamicTest.dynamicTest("invalid map " + value, () -> {
                assertThatThrownBy(() -> WitnessUtil.getRedeemerMapBytes(hex(value)))
                        .isInstanceOf(CborException.class);
                assertThatThrownBy(() -> WitnessUtil.getWitnessFields(hex(value))).isInstanceOf(CborException.class);
            }));
        }
        return tests.stream();
    }

    /** Exercise the first natural four-byte length, rather than only non-minimal small length arguments. */
    @Test
    void largeArrayLengthCrossesTheTwoByteBoundary() throws Exception {
        for (int count : new int[]{65535, 65536}) {
            int width = count == 65535 ? 2 : 4;
            List<byte[]> items = WitnessUtil.getArrayBytes(hex("d90102" + header(4, count, width)
                    + "00".repeat(count)));
            assertThat(items).hasSize(count);
            for (byte[] item : items) assertThat(item).isEqualTo(new byte[]{0});
        }
    }

    /** Witness field identifiers must be unsigned integers, even when other CBOR values have valid framing. */
    @Test
    void witnessKeysMustBeUnsignedIntegers() {
        for (String key : new String[]{"20", "40", "60", "80", "a0", "f5"}) {
            assertThatThrownBy(() -> WitnessUtil.getWitnessFields(hex("a1" + key + "00")))
                    .isInstanceOf(CborException.class).hasMessage("Expected unsigned witness field key");
        }
    }

    /** Encode a container header with a chosen argument width; -1 selects an indefinite container. */
    private static String header(int major, int count, int width) {
        if (width == -1) return String.format("%02x", major * 32 + 31);
        if (width == 0) return String.format("%02x", major * 32 + count);
        int additional = width == 1 ? 24 : width == 2 ? 25 : width == 4 ? 26 : 27;
        return String.format("%02x%0" + (width * 2) + "x", major * 32 + additional, count);
    }

    /** An indefinite container ends with BREAK (ff); definite containers have no terminator. */
    private static String end(int width) { return width == -1 ? "ff" : ""; }
    /** Decode a literal CBOR example used by these tests. */
    private static byte[] hex(String hex) { return HexUtil.decodeHexString(hex); }
}
