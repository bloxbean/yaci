package com.bloxbean.cardano.yaci.core.protocol.blockfetch.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.messages.BatchDone;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;

public enum BatchDoneSerializer implements Serializer<BatchDone> {
    INSTANCE;

    @Override
    public byte[] serialize(BatchDone object) {
        Array array = new Array();
        array.add(new UnsignedInteger(5));

        return CborSerializationUtil.serialize(array);
    }

    @Override
    public BatchDone deserialize(byte[] bytes) {
        DataItem di = CborSerializationUtil.deserializeOne(bytes);

        if (di instanceof Array) {
            int key = ((UnsignedInteger)((Array) di).getDataItems().get(0)).getValue().intValue();
            if (key == 5)
                return new BatchDone();
            else
                return null;
        } else
            return null;
    }
}
