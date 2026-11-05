package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.transaction.raw.RawInput;
import com.bloxbean.cardano.client.transaction.util.RawModels;
import com.bloxbean.cardano.yaci.core.model.TransactionInput;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

/** Maps inputs read by {@link RawInput} to Yaci's model (ADR 0003 D8). */
public enum TransactionInputSerializer implements Serializer<TransactionInput> {
    INSTANCE;

    /**
     * @deprecated A decoded input has lost its source encoding. Use {@link #read(RawInput)}, or
     * {@link BlockSerializer} for a block.
     */
    @Deprecated(since = "0.5.0", forRemoval = true)
    @Override
    public TransactionInput deserializeDI(DataItem di) {
        return read(RawInput.of(RawModels.span(di)));
    }

    /**
     * @param input an input as received
     * @return the input
     */
    public TransactionInput read(RawInput input) {
        return TransactionInput.builder()
                .transactionId(HexUtil.encodeHexString(input.transactionId()))
                .index(input.index())
                .build();
    }
}
