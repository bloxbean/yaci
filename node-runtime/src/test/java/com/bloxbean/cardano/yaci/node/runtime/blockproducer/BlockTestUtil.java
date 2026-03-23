package com.bloxbean.cardano.yaci.node.runtime.blockproducer;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;

/**
 * Shared test utilities for block builder tests.
 */
final class BlockTestUtil {

    private BlockTestUtil() {
        // utility class
    }

    /**
     * Unwrap tag-24 (wrapCBORinCBOR) block content from a parsed block array.
     * Block format: [era, 24(h'<inner_bytes>')] or [era, [content...]]
     * Returns the deserialized inner 5-element array: [header, txBodies, witnesses, auxData, invalidTxs]
     */
    public static Array unwrapTag24BlockContent(Array blockArray) {
        DataItem innerItem = blockArray.getDataItems().get(1);
        if (innerItem instanceof ByteString) {
            return (Array) CborSerializationUtil.deserializeOne(((ByteString) innerItem).getBytes());
        } else {
            return (Array) innerItem;
        }
    }
}
