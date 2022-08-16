package com.bloxbean.cardano.yaci.core.protocol.blockfetch.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.messages.StartBatch;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;

public enum StartBatchSerializer implements Serializer<StartBatch> {
    INSTANCE;

    @Override
    public byte[] serialize(StartBatch startBatch) {
        Array array = new Array();
        array.add(new UnsignedInteger(2));

        return CborSerializationUtil.serialize(array);
    }

    @Override
    public StartBatch deserialize(byte[] bytes) {
        DataItem di = CborSerializationUtil.deserialize(bytes);

        if (di instanceof Array) {
            int key = ((UnsignedInteger)((Array) di).getDataItems().get(0)).getValue().intValue();
            if (key == 2)
                return new StartBatch();
            else
                return null;
        } else
            return null;
    }
}
