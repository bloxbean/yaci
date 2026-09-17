package com.bloxbean.cardano.yaci.core.util;

import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.encoder.ArrayEncoder;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.MajorType;

import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;

/** Stack-safe array traversal for the non-canonical network re-encoding path. */
final class ArrayCborEncoder extends CborEncoder {
    private final ArrayHeaderEncoder headers;

    ArrayCborEncoder(OutputStream output) {
        super(output);
        nonCanonical();
        headers = new ArrayHeaderEncoder(this, output);
    }

    @Override
    public void encode(DataItem item) throws CborException {
        if (!(item instanceof Array)) {
            super.encode(item);
            return;
        }
        Deque<Iterator<DataItem>> arrays = new ArrayDeque<>();
        arrays.push(Collections.singletonList(item).iterator());
        while (!arrays.isEmpty()) {
            Iterator<DataItem> current = arrays.peek();
            if (!current.hasNext()) {
                arrays.pop();
                continue;
            }
            DataItem next = current.next();
            if (next instanceof Array) {
                Array array = (Array) next;
                if (array.hasTag()) {
                    super.encode(array.getTag());
                }
                headers.encode(array);
                arrays.push(array.getDataItems().iterator());
            } else {
                super.encode(next);
            }
        }
    }

    private static final class ArrayHeaderEncoder extends ArrayEncoder {
        private ArrayHeaderEncoder(CborEncoder encoder, OutputStream output) {
            super(encoder, output);
        }

        @Override
        public void encode(Array array) throws CborException {
            if (array.isChunked()) {
                encodeTypeChunked(MajorType.ARRAY);
            } else {
                encodeTypeAndLength(MajorType.ARRAY, array.getDataItems().size());
            }
        }
    }
}
