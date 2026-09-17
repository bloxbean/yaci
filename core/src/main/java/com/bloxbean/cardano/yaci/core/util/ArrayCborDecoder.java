package com.bloxbean.cardano.yaci.core.util;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.decoder.ArrayDecoder;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Special;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** Decodes consecutive nested arrays without consuming one Java stack frame per array. */
public final class ArrayCborDecoder extends CborDecoder {
    private final PushbackInputStream input;
    private final ArrayLengthDecoder lengths;
    // Keep allocation bounded even for an untrusted declared length.
    private int maxArrayPreallocationSize = 4096;

    @Override
    public void setMaxPreallocationSize(int size) {
        super.setMaxPreallocationSize(size);
        maxArrayPreallocationSize = size;
    }

    public ArrayCborDecoder(InputStream input) {
        this(new PushbackInputStream(input));
    }

    private ArrayCborDecoder(PushbackInputStream input) {
        super(input);
        this.input = input;
        this.lengths = new ArrayLengthDecoder(this, input);
    }

    public static List<DataItem> decode(byte[] bytes) throws CborException {
        return new ArrayCborDecoder(new ByteArrayInputStream(bytes)).decode();
    }

    @Override
    public DataItem decodeNext() throws CborException {
        Deque<Frame> arrays = null;
        try {
            while (true) {
                int symbol = input.read();
                DataItem item;
                if (symbol == -1) {
                    if (arrays == null || arrays.isEmpty()) {
                        return null;
                    }
                    throw new CborException("Unexpected end of stream");
                }
                if ((symbol >>> 5) == 4) {
                    long length = lengths.length(symbol);
                    Array array = length >= 0 ? new Array((int) Math.min(length,
                            maxArrayPreallocationSize > 0 ? Math.min(maxArrayPreallocationSize, 4096) : 4096))
                            : new Array();
                    array.setChunked(length == -1);
                    if (length > 0 || (length == -1 && isAutoDecodeInfinitiveArrays())) {
                        if (arrays == null) {
                            arrays = new ArrayDeque<>();
                        }
                        arrays.push(new Frame(array, length));
                        continue;
                    }
                    item = array;
                } else {
                    input.unread(symbol);
                    // Maps, tags and scalar semantics remain those of the library. Its calls
                    // back into decodeNext also use this iterative array implementation.
                    item = super.decodeNext();
                }
                while (arrays != null && !arrays.isEmpty()) {
                    Frame frame = arrays.peek();
                    frame.array.add(item);
                    if (frame.remaining == -1) {
                        if (!Special.BREAK.equals(item)) {
                            break;
                        }
                    } else if (--frame.remaining != 0) {
                        break;
                    }
                    item = arrays.pop().array;
                }
                if (arrays == null || arrays.isEmpty()) {
                    return item;
                }
            }
        } catch (IOException e) {
            throw new CborException(e);
        }
    }

    private static final class Frame {
        private final Array array;
        private long remaining;

        private Frame(Array array, long remaining) {
            this.array = array;
            this.remaining = remaining;
        }
    }

    private static final class ArrayLengthDecoder extends ArrayDecoder {
        private ArrayLengthDecoder(CborDecoder decoder, InputStream input) {
            super(decoder, input);
        }

        private long length(int symbol) throws CborException {
            return getLength(symbol);
        }
    }
}
