package com.bloxbean.cardano.yaci.core.util;

import co.nstant.in.cbor.CborDecoder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CborByteScannerTest {

    @Test
    void endOffsetIncludesBreakForEmptyIndefiniteMap() {
        byte[] bytes = bytes(0xbf, 0xff);

        assertEquals(2, CborByteScanner.endOffset(bytes));
    }

    @Test
    void endOffsetIncludesNestedIndefiniteItems() {
        byte[] bytes = bytes(0x82, 0xbf, 0xff, 0x9f, 0x01, 0xff);

        assertEquals(6, CborByteScanner.endOffset(bytes));
    }

    @Test
    void completeTopLevelItemsPreservesOriginalBytes() {
        byte[] bytes = bytes(0x82, 0x01, 0x02, 0x18, 0x00);

        List<CborByteScanner.CborSlice> slices = CborByteScanner.completeTopLevelItems(bytes);

        assertEquals(2, slices.size());
        assertEquals(CborByteScanner.MAJOR_TYPE_ARRAY, slices.get(0).majorType());
        assertEquals(CborByteScanner.MAJOR_TYPE_UNSIGNED_INTEGER, slices.get(1).majorType());
        assertArrayEquals(bytes(0x82, 0x01, 0x02), slices.get(0).copyFrom(bytes));
        assertArrayEquals(bytes(0x18, 0x00), slices.get(1).copyFrom(bytes));
    }

    @Test
    void completeTopLevelItemsStopsBeforeIncompleteTrailingItem() {
        byte[] bytes = bytes(0x82, 0x01, 0x02, 0x43, 0xaa);

        List<CborByteScanner.CborSlice> slices = CborByteScanner.completeTopLevelItems(bytes);

        assertEquals(1, slices.size());
        assertArrayEquals(bytes(0x82, 0x01, 0x02), slices.get(0).copyFrom(bytes));
    }

    @Test
    void endOffsetFailsForIncompleteItem() {
        var exception = assertThrows(CborByteScanner.IncompleteCborException.class,
                () -> CborByteScanner.endOffset(bytes(0x82, 0x01)));

        assertEquals(0, exception.getStackTrace().length);
    }

    @Test
    void endOffsetAcceptsUint64ArgumentsWithHighBitSetForIntegerValues() {
        assertEquals(9, CborByteScanner.endOffset(bytes(
                0x1b, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff)));
        assertEquals(9, CborByteScanner.endOffset(bytes(
                0x3b, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff)));
    }

    @Test
    void endOffsetAcceptsUint64ArgumentsWithHighBitSetForTags() {
        assertEquals(10, CborByteScanner.endOffset(bytes(
                0xdb, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x01)));
    }

    @Test
    void endOffsetRejectsLengthTooLargeForJavaByteArray() {
        assertThrows(IllegalArgumentException.class,
                () -> CborByteScanner.endOffset(bytes(0x5a, 0xff, 0xff, 0xff, 0xff)));
    }

    @Test
    void endOffsetRejectsExcessiveNestingBeforeStackOverflow() {
        byte[] bytes = new byte[1100];
        for (int i = 0; i < bytes.length - 1; i++) {
            bytes[i] = (byte) 0x81;
        }
        bytes[bytes.length - 1] = 0x00;

        assertThrows(IllegalArgumentException.class, () -> CborByteScanner.endOffset(bytes));
    }

    @Test
    void completeTopLevelItemsReportsTaggedArrayContentMajorType() {
        byte[] bytes = bytes(0xc0, 0x81, 0x01);

        List<CborByteScanner.CborSlice> slices = CborByteScanner.completeTopLevelItems(bytes);

        assertEquals(1, slices.size());
        assertEquals(CborByteScanner.MAJOR_TYPE_TAG, slices.get(0).majorType());
        assertEquals(CborByteScanner.MAJOR_TYPE_ARRAY, slices.get(0).untaggedMajorType());
    }

    @Test
    void completeTopLevelItemsCanResumeFromOffset() {
        byte[] bytes = bytes(0x81, 0x01, 0x82, 0x02, 0x03);

        List<CborByteScanner.CborSlice> slices = CborByteScanner.completeTopLevelItems(bytes, 2);

        assertEquals(1, slices.size());
        assertEquals(2, slices.get(0).startOffset());
        assertEquals(5, slices.get(0).endOffset());
    }

    @Test
    void endOffsetRejectsTopLevelBreak() {
        assertThrows(IllegalArgumentException.class, () -> CborByteScanner.endOffset(bytes(0xff)));
    }

    @Test
    void endOffsetRejectsNestedIndefiniteStringChunks() {
        assertThrows(IllegalArgumentException.class,
                () -> CborByteScanner.endOffset(bytes(0x5f, 0x5f, 0x40, 0xff, 0xff)));
    }

    @Test
    void endOffsetRejectsContainerCountAboveJavaSignedLongRange() {
        assertThrows(IllegalArgumentException.class,
                () -> CborByteScanner.endOffset(bytes(0x9b, 0x80, 0x00, 0x00, 0x00,
                        0x00, 0x00, 0x00, 0x00)));
    }

    @Test
    void generatedValidCborMatchesCborJavaBoundaries() throws Exception {
        Random random = new Random(0x5eed);
        List<byte[]> sequence = new ArrayList<>();
        ByteArrayOutputStream concatenated = new ByteArrayOutputStream();

        for (int i = 0; i < 5_000; i++) {
            byte[] value = randomCbor(random, 0);
            CborDecoder.decode(value);
            assertEquals(value.length, CborByteScanner.endOffset(value), "case " + i);

            sequence.add(value);
            concatenated.write(value);
        }

        byte[] bytes = concatenated.toByteArray();
        List<CborByteScanner.CborSlice> slices = CborByteScanner.completeTopLevelItems(bytes);

        assertEquals(sequence.size(), slices.size());
        for (int i = 0; i < sequence.size(); i++) {
            assertArrayEquals(sequence.get(i), slices.get(i).copyFrom(bytes), "slice " + i);
        }
    }

    private byte[] bytes(int... values) {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            bytes[i] = (byte) values[i];
        }
        return bytes;
    }

    private byte[] randomCbor(Random random, int depth) {
        int choice = depth > 3 ? random.nextInt(5) : random.nextInt(10);
        return switch (choice) {
            case 0 -> encodeUnsigned(0, randomArgument(random));
            case 1 -> encodeUnsigned(1, randomArgument(random));
            case 2 -> randomByteString(random);
            case 3 -> randomTextString(random);
            case 4 -> randomSimple(random);
            case 5 -> randomArray(random, depth);
            case 6 -> randomMap(random, depth);
            case 7 -> randomIndefiniteArray(random, depth);
            case 8 -> concat(encodeUnsigned(6, 1_000 + random.nextInt(10_000)), randomCbor(random, depth + 1));
            default -> randomIndefiniteByteString(random);
        };
    }

    private long randomArgument(Random random) {
        return switch (random.nextInt(5)) {
            case 0 -> random.nextInt(24);
            case 1 -> random.nextInt(256);
            case 2 -> random.nextInt(65_536);
            case 3 -> random.nextLong(1L << 32);
            default -> random.nextLong(Long.MAX_VALUE);
        };
    }

    private byte[] randomByteString(Random random) {
        byte[] data = new byte[random.nextInt(16)];
        random.nextBytes(data);
        return concat(encodeUnsigned(2, data.length), data);
    }

    private byte[] randomTextString(Random random) {
        int length = random.nextInt(16);
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) ('a' + random.nextInt(26));
        }
        return concat(encodeUnsigned(3, data.length), data);
    }

    private byte[] randomSimple(Random random) {
        return switch (random.nextInt(5)) {
            case 0 -> bytes(0xf4);
            case 1 -> bytes(0xf5);
            case 2 -> bytes(0xf6);
            case 3 -> bytes(0xf7);
            default -> bytes(0xf8, 32 + random.nextInt(224));
        };
    }

    private byte[] randomArray(Random random, int depth) {
        int length = random.nextInt(4);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(encodeUnsigned(4, length));
        for (int i = 0; i < length; i++) {
            out.writeBytes(randomCbor(random, depth + 1));
        }
        return out.toByteArray();
    }

    private byte[] randomMap(Random random, int depth) {
        int length = random.nextInt(4);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(encodeUnsigned(5, length));
        for (int i = 0; i < length; i++) {
            out.writeBytes(randomCbor(random, depth + 1));
            out.writeBytes(randomCbor(random, depth + 1));
        }
        return out.toByteArray();
    }

    private byte[] randomIndefiniteArray(Random random, int depth) {
        int length = random.nextInt(4);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x9f);
        for (int i = 0; i < length; i++) {
            out.writeBytes(randomCbor(random, depth + 1));
        }
        out.write(0xff);
        return out.toByteArray();
    }

    private byte[] randomIndefiniteByteString(Random random) {
        int chunks = random.nextInt(4);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x5f);
        for (int i = 0; i < chunks; i++) {
            out.writeBytes(randomByteString(random));
        }
        out.write(0xff);
        return out.toByteArray();
    }

    private byte[] encodeUnsigned(int majorType, long argument) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int prefix = majorType << 5;
        if (argument < 24) {
            out.write(prefix | (int) argument);
        } else if (argument <= 0xff) {
            out.write(prefix | 24);
            out.write((int) argument);
        } else if (argument <= 0xffff) {
            out.write(prefix | 25);
            out.write((int) (argument >>> 8));
            out.write((int) argument);
        } else if (argument <= 0xffffffffL) {
            out.write(prefix | 26);
            for (int i = 3; i >= 0; i--) {
                out.write((int) (argument >>> (8 * i)));
            }
        } else {
            out.write(prefix | 27);
            for (int i = 7; i >= 0; i--) {
                out.write((int) (argument >>> (8 * i)));
            }
        }
        return out.toByteArray();
    }

    private byte[] concat(byte[]... chunks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] chunk : chunks) {
            out.writeBytes(chunk);
        }
        return out.toByteArray();
    }
}
