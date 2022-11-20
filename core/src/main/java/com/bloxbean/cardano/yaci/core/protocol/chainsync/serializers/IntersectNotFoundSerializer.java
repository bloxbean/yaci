package com.bloxbean.cardano.yaci.core.protocol.chainsync.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.IntersectNotFound;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;

public enum IntersectNotFoundSerializer implements Serializer<IntersectNotFound> {
    INSTANCE();

    @Override
    public byte[] serialize(IntersectNotFound object) {
        return new byte[0];
    }

    public IntersectNotFound deserialize(byte[] bytes) {
        DataItem di = CborSerializationUtil.deserializeOne(bytes);
        Array array = (Array) di;
        int key = ((UnsignedInteger) array.getDataItems().get(0)).getValue().intValue();
        if (key != 6)
            return null;

        IntersectNotFound intersectNotFound
                = new IntersectNotFound(TipSerializer.INSTANCE.deserializeDI(array.getDataItems().get(1)));
        return intersectNotFound;
    }
}
