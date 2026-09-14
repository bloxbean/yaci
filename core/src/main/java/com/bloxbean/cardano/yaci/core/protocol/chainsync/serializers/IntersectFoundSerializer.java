package com.bloxbean.cardano.yaci.core.protocol.chainsync.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.IntersectFound;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;

public enum IntersectFoundSerializer implements Serializer<IntersectFound> {
    INSTANCE();

    @Override
    public IntersectFound deserialize(byte[] bytes) {
        DataItem di = CborSerializationUtil.deserializeOne(bytes);
        Array array = (Array) di;
        int key = ((UnsignedInteger) array.getDataItems().get(0)).getValue().intValue();
        if (key != 5)
            return null;

        Point point = PointSerializer.INSTANCE.deserializeDI(array.getDataItems().get(1));
        Tip tip = TipSerializer.INSTANCE.deserializeDI(array.getDataItems().get(2));
        IntersectFound intersectFound = new IntersectFound(point, tip);

        return intersectFound;
    }

    @Override
    public DataItem serializeDI(IntersectFound inFnd) {
        Array array = new Array();
        array.add(new UnsignedInteger(5));
        array.add(PointSerializer.INSTANCE.serializeDI(inFnd.getPoint()));
        array.add(TipSerializer.INSTANCE.serializeDI(inFnd.getTip()));
        return array;
    }
}
