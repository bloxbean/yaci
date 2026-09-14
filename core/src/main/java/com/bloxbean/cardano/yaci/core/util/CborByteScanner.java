package com.bloxbean.cardano.yaci.core.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Byte-level CBOR scanner for finding data item boundaries without decoding and re-encoding values.
 *
 * <p>Use this class when the exact original bytes matter. Examples include network framing, block
 * hashing, datum/redeemer hashing, and any opaque payload that must be passed through unchanged.
 * The scanner walks the CBOR structure just far enough to answer "where does this data item end?"
 * It does not build {@code DataItem} objects, sort map keys, canonicalize integers, or otherwise
 * rewrite the input.
 *
 * <p>CBOR starts every data item with an initial byte. The high 3 bits are the major type and the
 * low 5 bits are the additional information:
 *
 * <pre>
 * 0x82 0x01 0x02
 * ^^^^
 * 0x82 = major type 4 (array), additional info 2 (two elements)
 *
 * This is the CBOR array [1, 2]. The whole item occupies bytes 0..3, so endOffset(..., 0) returns 3.
 * </pre>
 *
 * <p>Nested items are included in the returned boundary:
 *
 * <pre>
 * 0x82 0xbf 0xff 0x9f 0x01 0xff
 *      ^^^^^^^^^    ^^^^^^^^^^^^^^
 *      empty map    indefinite array [1]
 *
 * This is [ {}, [1] ] where both nested values use indefinite-length encodings. The scanner returns
 * the offset after the final 0xff break byte. Those break bytes are part of the original wire bytes
 * and are preserved by callers that slice using the returned offsets.
 * </pre>
 *
 * <p>For a stream containing multiple top-level items:
 *
 * <pre>
 * 0x82 0x01 0x02 0x18 0x00
 * ^^^^^^^^^^^^^^ ^^^^^^^^^
 * array [1, 2]   integer 0 encoded in two bytes
 * </pre>
 *
 * {@link #completeTopLevelItems(byte[])} returns two slices, one for each top-level data item.
 * The integer slice remains {@code 0x18 0x00}; it is not normalized to canonical {@code 0x00}.
 *
 * <p>This class is intentionally generic CBOR plumbing. It does not know Cardano CDDL, transaction
 * layout, or mini-protocol message grouping rules.
 */
public final class CborByteScanner {
    public static final int MAJOR_TYPE_UNSIGNED_INTEGER = 0;
    public static final int MAJOR_TYPE_NEGATIVE_INTEGER = 1;
    public static final int MAJOR_TYPE_BYTE_STRING = 2;
    public static final int MAJOR_TYPE_TEXT_STRING = 3;
    public static final int MAJOR_TYPE_ARRAY = 4;
    public static final int MAJOR_TYPE_MAP = 5;
    public static final int MAJOR_TYPE_TAG = 6;
    public static final int MAJOR_TYPE_SIMPLE = 7;

    // Cardano values are nowhere near this depth in normal traffic. The limit is intentionally
    // generous, but finite, so malformed inputs cannot recurse until the JVM stack fails.
    private static final int MAX_NESTING_DEPTH = 1024;
    private static final long INDEFINITE_LENGTH = -1L;

    private CborByteScanner() {
    }

    /**
     * Returns the offset immediately after the complete CBOR data item starting at {@code offset}.
     *
     * <p>For example, for {@code 82 01 02}, {@code endOffset(bytes, 0)} returns {@code 3}.
     * For {@code bf ff} (an empty indefinite-length map), it returns {@code 2}, preserving the
     * break byte in the item boundary.
     *
     * @param bytes source bytes containing at least one CBOR data item
     * @param offset start offset of the data item
     * @return offset immediately after the complete data item
     * @throws IncompleteCborException if the buffer ends before the item is complete
     * @throws IllegalArgumentException if the item is malformed CBOR
     */
    public static int endOffset(byte[] bytes, int offset) {
        return skipItem(bytes, offset);
    }

    /**
     * Returns the offset immediately after the first CBOR data item in {@code bytes}.
     *
     * @param bytes source bytes containing at least one CBOR data item
     * @return offset immediately after the first complete data item
     * @throws IncompleteCborException if the buffer ends before the item is complete
     * @throws IllegalArgumentException if the item is malformed CBOR
     */
    public static int endOffset(byte[] bytes) {
        return endOffset(bytes, 0);
    }

    /**
     * Scans consecutive complete top-level CBOR data items from the start of {@code bytes}.
     *
     * <p>This method is useful for streaming buffers. If the final top-level item is incomplete,
     * scanning stops before it and the already-complete slices are returned. For example,
     * {@code 82 01 02 43 aa} returns one slice for {@code 82 01 02} and leaves the incomplete
     * byte string {@code 43 aa} for the caller's buffer.
     *
     * @param bytes source bytes containing zero or more top-level CBOR items
     * @return complete top-level slices in source order
     * @throws IllegalArgumentException if a malformed complete item is encountered
     */
    public static List<CborSlice> completeTopLevelItems(byte[] bytes) {
        return completeTopLevelItems(bytes, 0);
    }

    /**
     * Scans consecutive complete top-level CBOR data items from {@code offset}.
     *
     * <p>The returned slice offsets are still relative to the original {@code bytes} array, not
     * to {@code offset}. This lets streaming callers cache completed slices and resume scanning
     * only from the first not-yet-complete top-level item after more bytes arrive.
     *
     * @param bytes source bytes containing zero or more top-level CBOR items
     * @param offset start offset for scanning
     * @return complete top-level slices in source order
     * @throws IllegalArgumentException if a malformed complete item is encountered
     */
    public static List<CborSlice> completeTopLevelItems(byte[] bytes, int offset) {
        if (offset < 0 || offset > bytes.length)
            throw new IllegalArgumentException("Invalid CBOR scan offset: " + offset);

        List<CborSlice> slices = new ArrayList<>(3);
        while (offset < bytes.length) {
            int itemStart = offset;
            try {
                int itemEnd = endOffset(bytes, itemStart);
                slices.add(new CborSlice(itemStart, itemEnd, majorType(bytes[itemStart]),
                        untaggedMajorType(bytes, itemStart)));
                offset = itemEnd;
            } catch (IncompleteCborException e) {
                break;
            }
        }

        return slices;
    }

    /**
     * Returns the CBOR major type encoded in an initial byte.
     *
     * <p>The major type is the high 3 bits. For example, {@code 0x82} has major type 4,
     * which means array.
     *
     * @param initialByte first byte of a CBOR data item
     * @return major type number, from 0 to 7
     */
    public static int majorType(byte initialByte) {
        return (initialByte & 0xff) >> 5;
    }

    /**
     * Returns the major type after skipping any leading CBOR tags.
     *
     * <p>For example, {@code c0 82 01 02} is a tag-0 item whose content is the array
     * {@code [1, 2]}. {@link #majorType(byte)} returns {@link #MAJOR_TYPE_TAG} for the
     * first byte, while this method returns {@link #MAJOR_TYPE_ARRAY}.
     *
     * @param bytes source bytes containing at least one CBOR data item
     * @param offset start offset of the data item
     * @return major type of the first non-tag item
     * @throws IncompleteCborException if the buffer ends before the untagged item starts
     * @throws IllegalArgumentException if a tag has malformed additional information
     */
    public static int untaggedMajorType(byte[] bytes, int offset) {
        int cursor = offset;
        int depth = 0;
        while (true) {
            requireAvailable(bytes, cursor, 1);
            if (isBreak(bytes[cursor]))
                throw new IllegalArgumentException("CBOR break is only valid inside an indefinite-length item");

            int initialByte = bytes[cursor] & 0xff;
            int majorType = initialByte >> 5;
            if (majorType != MAJOR_TYPE_TAG)
                return majorType;

            if (++depth > MAX_NESTING_DEPTH)
                throw new IllegalArgumentException("CBOR nesting exceeds maximum depth: " + MAX_NESTING_DEPTH);

            cursor = skipArgument(bytes, cursor + 1, initialByte & 0x1f);
        }
    }

    /**
     * A byte slice for one complete CBOR data item.
     *
     * @param startOffset inclusive start offset in the original source byte array
     * @param endOffset exclusive end offset in the original source byte array
     * @param majorType CBOR major type of the top-level item
     * @param untaggedMajorType major type after skipping leading tags, same as {@code majorType} for untagged items
     */
    public record CborSlice(int startOffset, int endOffset, int majorType, int untaggedMajorType) {
        public int length() {
            return endOffset - startOffset;
        }

        public byte[] copyFrom(byte[] source) {
            return Arrays.copyOfRange(source, startOffset, endOffset);
        }
    }

    /**
     * Indicates that the current byte buffer ends before a complete CBOR item could be scanned.
     *
     * <p>Streaming callers usually keep the buffered bytes and try again after more bytes arrive.
     */
    public static class IncompleteCborException extends RuntimeException {
        public IncompleteCborException() {
            super("Incomplete CBOR data item", null, false, false);
        }
    }

    private static int skipItem(byte[] bytes, int offset) {
        return skipItem(bytes, offset, 0);
    }

    private static int skipItem(byte[] bytes, int offset, int depth) {
        if (depth > MAX_NESTING_DEPTH)
            throw new IllegalArgumentException("CBOR nesting exceeds maximum depth: " + MAX_NESTING_DEPTH);

        requireAvailable(bytes, offset, 1);
        if (isBreak(bytes[offset]))
            throw new IllegalArgumentException("CBOR break is only valid inside an indefinite-length item");

        int initialByte = bytes[offset] & 0xff;
        int majorType = initialByte >> 5;
        int additionalInfo = initialByte & 0x1f;
        int cursor = offset + 1;

        return switch (majorType) {
            case MAJOR_TYPE_UNSIGNED_INTEGER, MAJOR_TYPE_NEGATIVE_INTEGER ->
                    skipArgument(bytes, cursor, additionalInfo);
            case MAJOR_TYPE_BYTE_STRING, MAJOR_TYPE_TEXT_STRING ->
                    skipByteOrTextString(bytes, cursor, majorType, additionalInfo);
            case MAJOR_TYPE_ARRAY -> skipArray(bytes, cursor, additionalInfo, depth);
            case MAJOR_TYPE_MAP -> skipMap(bytes, cursor, additionalInfo, depth);
            case MAJOR_TYPE_TAG -> {
                int nextOffset = skipArgument(bytes, cursor, additionalInfo);
                yield skipItem(bytes, nextOffset, depth + 1);
            }
            case MAJOR_TYPE_SIMPLE -> skipSimpleValue(bytes, cursor, additionalInfo);
            default -> throw new IllegalArgumentException("Unsupported CBOR major type: " + majorType);
        };
    }

    private static int skipByteOrTextString(byte[] bytes, int cursor, int majorType, int additionalInfo) {
        LengthArgument lengthArgument = readLength(bytes, cursor, additionalInfo);
        if (lengthArgument.length() == INDEFINITE_LENGTH)
            return skipIndefiniteByteOrTextString(bytes, lengthArgument.nextOffset(), majorType);

        return addLength(lengthArgument.nextOffset(), lengthArgument.length(), bytes.length);
    }

    private static int skipArray(byte[] bytes, int cursor, int additionalInfo, int depth) {
        LengthArgument lengthArgument = readLength(bytes, cursor, additionalInfo);
        cursor = lengthArgument.nextOffset();
        if (lengthArgument.length() == INDEFINITE_LENGTH) {
            while (true) {
                requireAvailable(bytes, cursor, 1);
                if (isBreak(bytes[cursor]))
                    return cursor + 1;
                cursor = skipItem(bytes, cursor, depth + 1);
            }
        }

        for (long i = 0; i < lengthArgument.length(); i++) {
            cursor = skipItem(bytes, cursor, depth + 1);
        }
        return cursor;
    }

    private static int skipMap(byte[] bytes, int cursor, int additionalInfo, int depth) {
        LengthArgument lengthArgument = readLength(bytes, cursor, additionalInfo);
        cursor = lengthArgument.nextOffset();
        if (lengthArgument.length() == INDEFINITE_LENGTH) {
            while (true) {
                requireAvailable(bytes, cursor, 1);
                if (isBreak(bytes[cursor]))
                    return cursor + 1;

                cursor = skipItem(bytes, cursor, depth + 1);
                requireAvailable(bytes, cursor, 1);
                if (isBreak(bytes[cursor]))
                    throw new IllegalArgumentException("Indefinite CBOR map ended after a key without a value");
                cursor = skipItem(bytes, cursor, depth + 1);
            }
        }

        for (long i = 0; i < lengthArgument.length(); i++) {
            cursor = skipItem(bytes, cursor, depth + 1);
            cursor = skipItem(bytes, cursor, depth + 1);
        }
        return cursor;
    }

    private static int skipIndefiniteByteOrTextString(byte[] bytes, int cursor, int expectedMajorType) {
        while (true) {
            requireAvailable(bytes, cursor, 1);
            if (isBreak(bytes[cursor]))
                return cursor + 1;

            int initialByte = bytes[cursor] & 0xff;
            int majorType = initialByte >> 5;
            int additionalInfo = initialByte & 0x1f;
            if (majorType != expectedMajorType)
                throw new IllegalArgumentException("Invalid chunk major type for indefinite CBOR string");

            LengthArgument chunkLength = readLength(bytes, cursor + 1, additionalInfo);
            if (chunkLength.length() == INDEFINITE_LENGTH)
                throw new IllegalArgumentException("Nested indefinite CBOR string chunks are not allowed");
            cursor = addLength(chunkLength.nextOffset(), chunkLength.length(), bytes.length);
        }
    }

    private static int skipSimpleValue(byte[] bytes, int cursor, int additionalInfo) {
        return switch (additionalInfo) {
            case 24 -> addLength(cursor, 1, bytes.length);
            case 25 -> addLength(cursor, 2, bytes.length);
            case 26 -> addLength(cursor, 4, bytes.length);
            case 27 -> addLength(cursor, 8, bytes.length);
            case 28, 29, 30, 31 -> throw new IllegalArgumentException("Reserved CBOR simple value additional info");
            default -> cursor;
        };
    }

    private static LengthArgument readLength(byte[] bytes, int cursor, int additionalInfo) {
        if (additionalInfo == 31)
            return new LengthArgument(INDEFINITE_LENGTH, cursor);

        return readUnsignedLongArgument(bytes, cursor, additionalInfo);
    }

    private static int skipArgument(byte[] bytes, int cursor, int additionalInfo) {
        if (additionalInfo < 24)
            return cursor;

        return switch (additionalInfo) {
            case 24 -> addFixedWidth(cursor, 1, bytes.length);
            case 25 -> addFixedWidth(cursor, 2, bytes.length);
            case 26 -> addFixedWidth(cursor, 4, bytes.length);
            case 27 -> addFixedWidth(cursor, 8, bytes.length);
            case 28, 29, 30 -> throw new IllegalArgumentException("Reserved CBOR additional info");
            default -> throw new IllegalArgumentException("Indefinite length is not valid here");
        };
    }

    private static LengthArgument readUnsignedLongArgument(byte[] bytes, int cursor, int additionalInfo) {
        if (additionalInfo < 24)
            return new LengthArgument(additionalInfo, cursor);

        return switch (additionalInfo) {
            case 24 -> {
                requireAvailable(bytes, cursor, 1);
                yield new LengthArgument(bytes[cursor] & 0xffL, cursor + 1);
            }
            case 25 -> {
                requireAvailable(bytes, cursor, 2);
                long value = ((bytes[cursor] & 0xffL) << 8) | (bytes[cursor + 1] & 0xffL);
                yield new LengthArgument(value, cursor + 2);
            }
            case 26 -> {
                requireAvailable(bytes, cursor, 4);
                long value = ((bytes[cursor] & 0xffL) << 24)
                        | ((bytes[cursor + 1] & 0xffL) << 16)
                        | ((bytes[cursor + 2] & 0xffL) << 8)
                        | (bytes[cursor + 3] & 0xffL);
                yield new LengthArgument(value, cursor + 4);
            }
            case 27 -> {
                requireAvailable(bytes, cursor, 8);
                long value = 0;
                for (int i = 0; i < 8; i++) {
                    value = (value << 8) | (bytes[cursor + i] & 0xffL);
                }
                // Lengths and collection counts above Long.MAX_VALUE are valid CBOR numerically,
                // but cannot be represented safely by this Java scanner. Treat them as invalid
                // protocol input instead of wrapping negative and accepting malformed streams.
                if (value < 0)
                    throw new IllegalArgumentException("CBOR length exceeds Java signed long range");
                yield new LengthArgument(value, cursor + 8);
            }
            case 28, 29, 30 -> throw new IllegalArgumentException("Reserved CBOR additional info");
            default -> throw new IllegalArgumentException("Indefinite length is not valid here");
        };
    }

    private static int addLength(int cursor, long length, int bufferLength) {
        if (length < 0)
            throw new IllegalArgumentException("Negative CBOR length");
        if (length > Integer.MAX_VALUE)
            throw new IllegalArgumentException("CBOR length exceeds maximum supported Java byte array size");
        if (cursor > bufferLength - (int) length)
            throw new IncompleteCborException();
        return cursor + (int) length;
    }

    private static int addFixedWidth(int cursor, int length, int bufferLength) {
        if (cursor > bufferLength - length)
            throw new IncompleteCborException();
        return cursor + length;
    }

    private static void requireAvailable(byte[] bytes, int cursor, int neededBytes) {
        if (cursor < 0 || neededBytes < 0 || cursor > bytes.length - neededBytes)
            throw new IncompleteCborException();
    }

    private static boolean isBreak(byte value) {
        return (value & 0xff) == 0xff;
    }

    private record LengthArgument(long length, int nextOffset) {
    }
}
