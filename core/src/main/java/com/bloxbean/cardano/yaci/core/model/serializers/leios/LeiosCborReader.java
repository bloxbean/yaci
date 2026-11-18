package com.bloxbean.cardano.yaci.core.model.serializers.leios;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.DataItem;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.util.Arrays;

/**
 * Minimal CBOR cursor used when serializers need exact raw bytes for nested items.
 */
public final class LeiosCborReader {
    public static final int MAJOR_TYPE_BYTE_STRING = 2;
    public static final int MAJOR_TYPE_ARRAY = 4;
    public static final int MAJOR_TYPE_MAP = 5;
    public static final int MAJOR_TYPE_TAG = 6;
    public static final int BREAK = 0xff;
    public static final long INDEFINITE = -1;

    private final byte[] bytes;
    private int position;

    public LeiosCborReader(byte[] bytes) {
        this.bytes = bytes;
    }

    public int position() {
        return position;
    }

    public boolean hasRemaining() {
        return position < bytes.length;
    }

    public int peek() {
        ensureAvailable();
        return bytes[position] & 0xff;
    }

    public boolean nextIsBreak() {
        return hasRemaining() && peek() == BREAK;
    }

    public void readBreak() {
        int value = readByte();
        if (value != BREAK) {
            throw new IllegalArgumentException("expected CBOR break byte");
        }
    }

    public long readLength(int expectedMajorType) {
        int initialByte = readByte();
        int majorType = initialByte >> 5;
        int additionalInfo = initialByte & 31;
        if (majorType != expectedMajorType) {
            throw new IllegalArgumentException("expected CBOR major type " + expectedMajorType
                    + " but found " + majorType);
        }

        return switch (additionalInfo) {
            case 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23 ->
                    additionalInfo;
            case 24 -> readUnsigned(1).longValueExact();
            case 25 -> readUnsigned(2).longValueExact();
            case 26 -> readUnsigned(4).longValueExact();
            case 27 -> readUnsigned(8).longValueExact();
            case 31 -> INDEFINITE;
            default -> throw new IllegalArgumentException("reserved CBOR additional information: " + additionalInfo);
        };
    }

    public DecodedItem readDataItem() {
        int start = position;
        ByteArrayInputStream input = new ByteArrayInputStream(bytes, position, bytes.length - position);
        int before = input.available();
        DataItem dataItem;
        try {
            dataItem = new CborDecoder(input).decodeNext();
        } catch (CborException e) {
            throw new IllegalArgumentException("CBOR decode failed", e);
        }
        int consumed = before - input.available();
        position += consumed;
        return new DecodedItem(dataItem, Arrays.copyOfRange(bytes, start, position));
    }

    public void requireEnd() {
        if (hasRemaining()) {
            throw new IllegalArgumentException("unexpected trailing CBOR bytes");
        }
    }

    private BigInteger readUnsigned(int length) {
        if (position + length > bytes.length) {
            throw new IllegalArgumentException("unexpected end of CBOR data");
        }

        byte[] value = Arrays.copyOfRange(bytes, position, position + length);
        position += length;
        return new BigInteger(1, value);
    }

    private int readByte() {
        ensureAvailable();
        return bytes[position++] & 0xff;
    }

    private void ensureAvailable() {
        if (!hasRemaining()) {
            throw new IllegalArgumentException("unexpected end of CBOR data");
        }
    }

    public record DecodedItem(DataItem dataItem, byte[] rawBytes) {
    }
}
