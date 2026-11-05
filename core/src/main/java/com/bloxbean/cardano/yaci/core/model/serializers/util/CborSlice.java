package com.bloxbean.cardano.yaci.core.model.serializers.util;

import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.MajorType;
import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.client.exception.CborRuntimeException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A view of one complete value in its original CBOR buffer. The scanner only finds boundaries;
 * it never builds recursive DataItems, hashes map keys, or changes the encoding. Used by raw
 * witness extraction and the failure fallback. Invalid/truncated framing is rejected.
 * All ranges use an inclusive start and an exclusive end in the same source buffer.
 * This checks CBOR framing, not ledger rules or the semantic validity of Plutus data.
 *
 * <p>The walk is CCL's {@link CborSpan}. This class adds what its callers use and a span does not offer: reading
 * through tags, and {@link #arrayItem} reading a value without walking what follows it.</p>
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
    private static final int ARRAY = 4;
    private static final int TAG = 6;
    private static final int MAJOR_TYPE_SHIFT = 5;
    private static final int ADDITIONAL_INFO_MASK = 0x1f;
    private static final int BYTE_MASK = 0xff;
    private static final int FIRST_WIDTH_CODE = 24;
    private static final int LAST_WIDTH_CODE = 27;
    private static final int INDEFINITE = 31;

    private final CborSpan span;

    private CborSlice(CborSpan span) {
        this.span = span;
    }

    /** Validate that the buffer contains exactly one value, including all nested children. */
    public static CborSlice of(byte[] bytes) throws CborException {
        try {
            return new CborSlice(CborSpan.of(bytes));
        } catch (CborRuntimeException e) {
            throw new CborException(e.getMessage(), e);
        }
    }

    /**
     * Return the first value in a CBOR sequence, matching deserializeOne's existing block behavior.
     * Additional values are not returned, but their framing must still be valid.
     */
    public static CborSlice first(byte[] bytes) throws CborException {
        try {
            CborSpan first = CborSpan.at(bytes, 0);
            int position = first.length();
            while (position < bytes.length) position = CborSpan.skip(bytes, position, bytes.length);
            return new CborSlice(first);
        } catch (CborRuntimeException e) {
            throw new CborException(e.getMessage(), e);
        }
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
        try {
            int position = 0;
            for (int index : indexes) {
                position = arrayItems(bytes, position, index);
                for (int i = 0; i < index; i++) position = CborSpan.skip(bytes, position, bytes.length);
            }
            return new CborSlice(CborSpan.at(bytes, position));
        } catch (CborRuntimeException e) {
            throw new CborException(e.getMessage(), e);
        }
    }

    /**
     * The offset of the first item of the array at {@code position}, past any tags. A span must walk the whole array
     * first, which this exists to avoid.
     */
    private static int arrayItems(byte[] bytes, int position, int index) throws CborException {
        while (true) {
            if (position >= bytes.length) throw new CborException("Truncated CBOR header");
            int initial = bytes[position++] & BYTE_MASK;
            int major = initial >>> MAJOR_TYPE_SHIFT;
            int additional = initial & ADDITIONAL_INFO_MASK;
            long argument = additional;
            if (additional == INDEFINITE && major == ARRAY) {
                argument = -1;
            } else if (additional >= FIRST_WIDTH_CODE && additional <= LAST_WIDTH_CODE) {
                int width = 1 << (additional - FIRST_WIDTH_CODE);
                if (width > bytes.length - position) throw new CborException("Truncated CBOR argument");
                argument = 0;
                for (int i = 0; i < width; i++) argument = (argument << 8) | (bytes[position++] & BYTE_MASK);
                if (major == ARRAY && argument < 0) throw new CborException("Invalid CBOR array index");
            } else if (additional > LAST_WIDTH_CODE) {
                throw new CborException("Invalid CBOR additional information");
            }
            if (major == TAG) continue;
            if (major != ARRAY || index < 0 || (argument >= 0 && index >= argument)) {
                throw new CborException("Invalid CBOR array index");
            }
            return position;
        }
    }

    /** Copy the exact source encoding, retaining tags, map ordering, and non-minimal lengths. */
    public byte[] bytes() {
        return span.bytes();
    }

    /** Return the wrapped value's type, looking through any CBOR tags. */
    public MajorType type() throws CborException {
        return MajorType.ofByte(span.majorType() << MAJOR_TYPE_SHIFT);
    }

    /** Returns the innermost tag, matching DataItem.getTag(), or -1 when untagged. */
    public long tag() throws CborException {
        long tag = -1;
        for (CborSpan content = span; content.tag() != -1; content = content.untag()) tag = content.tag();
        return tag;
    }

    /**
     * Array elements, or alternating map keys/values, excluding container tags and final BREAK.
     * For {@code a1 00 81 01} ({@code {0: [1]}}), returns slices {@code 00} and {@code 81 01}.
     * Nested values stay intact; this method lists only the container's immediate children.
     */
    public List<CborSlice> items(MajorType expected) throws CborException {
        if ((expected != MajorType.ARRAY && expected != MajorType.MAP) || type() != expected) {
            throw new CborException("Expected CBOR " + expected);
        }
        CborSpan content = span;
        while (content.tag() != -1) content = content.untag();
        List<CborSlice> result = new ArrayList<>();
        if (expected == MajorType.ARRAY) {
            for (CborSpan item : content.items()) result.add(new CborSlice(item));
        } else {
            for (Map.Entry<CborSpan, CborSpan> entry : content.entries()) {
                result.add(new CborSlice(entry.getKey()));
                result.add(new CborSlice(entry.getValue()));
            }
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
        List<CborSpan> targets = new ArrayList<>();
        for (CborSlice value : values) targets.add(value.span);
        try {
            return span.replacing(targets, replacements);
        } catch (IllegalArgumentException e) {
            throw new CborException(e.getMessage(), e);
        }
    }
}
