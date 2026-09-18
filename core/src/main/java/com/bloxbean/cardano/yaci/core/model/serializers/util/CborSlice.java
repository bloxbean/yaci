package com.bloxbean.cardano.yaci.core.model.serializers.util;

import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.MajorType;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * A view of one complete value in its original CBOR buffer. The scanner only finds boundaries;
 * it never builds recursive DataItems, hashes map keys, or changes the encoding. Used by the
 * failure fallback, not the ordinary block-decoding path. Invalid/truncated framing is rejected.
 * All ranges use an inclusive start and an exclusive end in the same source buffer.
 * This checks CBOR framing, not ledger rules or the semantic validity of Plutus data.
 *
 * <p>Hex examples (spaces separate bytes):</p>
 * <pre>
 * 82 01 02       = [1, 2]        (definite array with two children)
 * 9f 01 02 ff    = [1, 2]        (indefinite array, closed by BREAK)
 * a1 00 81 01    = {0: [1]}      (one map pair; the value is another container)
 * d8 79 81 00    = 121([0])      (tag 121 wrapping an array: Plutus constructor 0)
 * </pre>
 */
public final class CborSlice {
    // CBOR major types occupy the upper three bits of the initial byte.
    private static final int BYTE_STRING = 2;
    private static final int TEXT_STRING = 3;
    private static final int ARRAY = 4;
    private static final int MAP = 5;
    private static final int TAG = 6;
    private static final int MAJOR_TYPE_SHIFT = 5;
    private static final int ADDITIONAL_INFO_MASK = 0x1f;
    private static final int BYTE_MASK = 0xff;
    private static final int BREAK = 0xff;

    // Lower-five-bit markers: values below 24 are encoded directly in the initial byte.
    private static final int ONE_BYTE_ARGUMENT = 24;
    private static final int EIGHT_BYTE_ARGUMENT = 27;
    private static final int INDEFINITE_ARGUMENT = 31;
    private static final long INDEFINITE_LENGTH = -1;
    private static final int ROOT_FRAME = -1; // Synthetic parent, not a CBOR major type.

    private final byte[] bytes;
    private final int start;
    private final int end;

    private CborSlice(byte[] bytes, int start, int end) {
        this.bytes = bytes;
        this.start = start;
        this.end = end;
    }

    /** Validate that the buffer contains exactly one value, including all nested children. */
    public static CborSlice of(byte[] bytes) throws CborException {
        int end = skip(bytes, 0, bytes.length);
        if (end != bytes.length) throw new CborException("Expected one complete CBOR value");
        return new CborSlice(bytes, 0, end);
    }

    /**
     * Return the first value in a CBOR sequence, matching deserializeOne's existing block behavior.
     * Additional values are not returned, but their framing must still be valid.
     */
    public static CborSlice first(byte[] bytes) throws CborException {
        int end = skip(bytes, 0, bytes.length);
        int position = end;
        while (position < bytes.length) position = skip(bytes, position, bytes.length);
        return new CborSlice(bytes, 0, end);
    }

    /**
     * Locate an array element by its index path without visiting later siblings. For example,
     * [0] reads the block envelope's era and [1, 0] reads its header without scanning its transactions.
     * This validates only the prefix and selected value; the caller must parse the rest separately.
     * Array/argument widths and indefinite arrays are handled here rather than assuming fixed offsets.
     * <pre>
     * 82 07 85 ... = [7, [header, transactions, witnesses, auxiliaryData, invalidTransactions]]
     * arrayItem(bytes, 0)    returns 07 (era), without visiting the block
     * arrayItem(bytes, 1, 0) returns the encoded header, without visiting transactions
     * </pre>
     */
    public static CborSlice arrayItem(byte[] bytes, int... indexes) throws CborException {
        int position = 0;
        for (int index : indexes) {
            Header container = header(bytes, position, bytes.length);
            while (container.major == TAG) container = header(bytes, container.end, bytes.length);
            if (container.major != ARRAY || index < 0
                    || (container.argument >= 0 && index >= container.argument)) {
                throw new CborException("Invalid CBOR array index");
            }
            position = container.end;
            for (int i = 0; i < index; i++) position = skip(bytes, position, bytes.length);
        }
        return new CborSlice(bytes, position, skip(bytes, position, bytes.length));
    }

    /** Copy the exact source encoding, retaining tags, map ordering, and non-minimal lengths. */
    public byte[] bytes() {
        return Arrays.copyOfRange(bytes, start, end);
    }

    /** Return the wrapped value's type, looking through any CBOR tags. */
    public MajorType type() throws CborException {
        return MajorType.ofByte(containerHeader().major << MAJOR_TYPE_SHIFT);
    }

    /** Returns the innermost tag, matching DataItem.getTag(), or -1 when untagged. */
    public long tag() throws CborException {
        int position = start;
        long tag = -1;
        Header header = header(bytes, position, end);
        while (header.major == TAG) {
            tag = header.argument;
            position = header.end;
            header = header(bytes, position, end);
        }
        return tag;
    }

    /**
     * Array elements, or alternating map keys/values, excluding container tags and final BREAK.
     * For {@code a1 00 81 01} ({@code {0: [1]}}), returns slices {@code 00} and {@code 81 01}.
     * Nested values stay intact; this method lists only the container's immediate children.
     */
    public List<CborSlice> items(MajorType expected) throws CborException {
        Header header = containerHeader();
        if ((expected != MajorType.ARRAY && expected != MajorType.MAP)
                || header.major != (expected == MajorType.ARRAY ? ARRAY : MAP)) {
            throw new CborException("Expected CBOR " + expected);
        }
        List<CborSlice> result = new ArrayList<>();
        int position = header.end;
        // of() already checked the entire value. An indefinite container's last byte is its BREAK;
        // child BREAKs belong to their own slices and are consumed by skip().
        int contentEnd = header.argument == INDEFINITE_LENGTH ? end - 1 : end;
        while (position < contentEnd) {
            int next = skip(bytes, position, contentEnd);
            result.add(new CborSlice(bytes, position, next));
            position = next;
        }
        return result;
    }

    /** Replace selected disjoint values in this slice, retaining all other source bytes verbatim. */
    public byte[] replacing(List<CborSlice> values, byte... replacements) throws CborException {
        List<byte[]> encoded = new ArrayList<>();
        for (byte replacement : replacements) encoded.add(new byte[]{replacement});
        return replacing(values, encoded);
    }

    /**
     * Build a temporary encoding using replacements in source order. Each replacement must encode
     * one complete value so the enclosing container's element count remains unchanged.
     * The caller supplies these encodings; this method only checks that source ranges do not overlap.
     */
    public byte[] replacing(List<CborSlice> values, List<byte[]> replacements) throws CborException {
        if (values.size() != replacements.size()) throw new CborException("Replacement count mismatch");
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        int position = start;
        int index = 0;
        for (CborSlice value : values) {
            if (value.bytes != bytes || value.start < position || value.end > end) {
                throw new CborException("Invalid CBOR replacement range");
            }
            result.write(bytes, position, value.start - position);
            byte[] replacement = replacements.get(index++);
            result.write(replacement, 0, replacement.length);
            position = value.end;
        }
        result.write(bytes, position, end - position);
        return result.toByteArray();
    }

    private Header containerHeader() throws CborException {
        Header header = header(bytes, start, end);
        while (header.major == TAG) header = header(bytes, header.end, end);
        return header;
    }

    /**
     * Return the offset immediately after one value. Frames on the heap replace recursive calls:
     * arrays count children, maps count keys and values, and tags require exactly one wrapped value.
     * A remaining count of -1 means an indefinite container, which must end with BREAK.
     * For {@code a1 00 81 01}, the map frame starts with two children (key/value). Reading key 0
     * leaves one. Reading the array consumes that value and pushes an array frame with one child.
     * Once integer 1 is consumed, both frames complete without recursive Java calls.
     */
    private static int skip(byte[] bytes, int position, int end) throws CborException {
        Deque<Frame> parents = new ArrayDeque<>();
        // The synthetic parent stops the scan after one root value, even when siblings follow it.
        parents.push(new Frame(ROOT_FRAME, 1));
        while (!parents.isEmpty()) {
            Frame parent = parents.peek();
            if (parent.remaining == 0) {
                parents.pop();
                continue;
            }
            if (position >= end) throw new CborException("Truncated CBOR value");
            if ((bytes[position] & BYTE_MASK) == BREAK) {
                // BREAK may close only an indefinite container. A map cannot end after a bare key.
                if (parent.remaining != INDEFINITE_LENGTH || (parent.major == MAP && parent.odd)) {
                    throw new CborException("Unexpected CBOR BREAK");
                }
                position++;
                parents.pop();
                continue;
            }
            Header item = header(bytes, position, end);
            // Indefinite byte/text strings contain definite chunks of the same string type.
            if ((parent.major == BYTE_STRING || parent.major == TEXT_STRING)
                    && (item.major != parent.major || item.argument == INDEFINITE_LENGTH)) {
                throw new CborException("Invalid indefinite string chunk");
            }
            if (parent.remaining > 0) parent.remaining--;
            parent.odd = !parent.odd;
            position = item.end;
            // CBOR major types: 2 = bytes, 3 = text, 4 = array, 5 = map, 6 = tag.
            switch (item.major) {
                case BYTE_STRING:
                case TEXT_STRING:
                    if (item.argument == INDEFINITE_LENGTH) {
                        parents.push(new Frame(item.major, INDEFINITE_LENGTH));
                    } else {
                        if (item.argument < 0 || item.argument > end - position) {
                            throw new CborException("Truncated CBOR string");
                        }
                        // Payload bytes are opaque: a 0xff inside a string is not a BREAK marker.
                        position += (int) item.argument;
                    }
                    break;
                case ARRAY:
                case MAP:
                    long count = item.argument;
                    if (count != INDEFINITE_LENGTH) {
                        if (count < 0 || count > end - position) {
                            throw new CborException("Invalid CBOR container length");
                        }
                        if (item.major == MAP) count *= 2; // A map length counts pairs, not values.
                    }
                    parents.push(new Frame(item.major, count));
                    break;
                case TAG:
                    parents.push(new Frame(TAG, 1));
                    break;
                default:
                    break; // Integer/simple/float payload was consumed by header().
            }
        }
        return position;
    }

    /**
     * Read a header and its numeric argument, leaving container children/string contents unread.
     * For example, {@code 82} has array type and length 2; {@code 98 18} has array type and a
     * one-byte length argument of 24. For {@code 58 40}, this consumes the byte-string header
     * and length 64, but leaves its 64 payload bytes for skip().
     */
    private static Header header(byte[] bytes, int position, int end) throws CborException {
        if (position >= end) throw new CborException("Truncated CBOR header");
        int initial = bytes[position++] & BYTE_MASK;
        // Upper three bits select the major type; lower five encode a value or its following width.
        int major = initial >>> MAJOR_TYPE_SHIFT;
        int additional = initial & ADDITIONAL_INFO_MASK;
        long argument;
        if (additional < ONE_BYTE_ARGUMENT) {
            argument = additional;
        } else if (additional <= EIGHT_BYTE_ARGUMENT) {
            // Additional information 24/25/26/27 means 1/2/4/8 following bytes, in big-endian order.
            int count = 1 << (additional - ONE_BYTE_ARGUMENT);
            if (count > end - position) throw new CborException("Truncated CBOR argument");
            argument = 0;
            for (int i = 0; i < count; i++) argument = (argument << 8) | (bytes[position++] & BYTE_MASK);
            // Unsigned 64-bit integers/tags and float bit patterns are legal. Lengths must fit the buffer.
            if (major >= BYTE_STRING && major <= MAP && argument < 0) {
                throw new CborException("CBOR length exceeds supported buffer size");
            }
        } else if (additional == INDEFINITE_ARGUMENT && major >= BYTE_STRING && major <= MAP) {
            // Only strings, arrays, and maps can have indefinite length.
            argument = INDEFINITE_LENGTH;
        } else {
            throw new CborException("Invalid CBOR additional information");
        }
        return new Header(major, argument, position);
    }

    private static final class Header {
        private final int major;
        private final long argument;
        private final int end;

        private Header(int major, long argument, int end) {
            this.major = major;
            this.argument = argument;
            this.end = end;
        }
    }

    private static final class Frame {
        private final int major;
        private long remaining;
        private boolean odd; // For maps, true means a key was seen and its value is still required.

        private Frame(int major, long remaining) {
            this.major = major;
            this.remaining = remaining;
        }
    }
}
