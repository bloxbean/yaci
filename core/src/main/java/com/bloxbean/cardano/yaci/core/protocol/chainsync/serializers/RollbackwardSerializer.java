package com.bloxbean.cardano.yaci.core.protocol.chainsync.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Rollbackward;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;

public enum RollbackwardSerializer implements Serializer<Rollbackward> {
    INSTANCE;

    @Override
    public Rollbackward deserialize(byte[] bytes) {
        Array array = (Array) CborSerializationUtil.deserializeOne(bytes);

        Point point = PointSerializer.INSTANCE.deserializeDI(array.getDataItems().get(1));
        Tip tip = TipSerializer.INSTANCE.deserializeDI(array.getDataItems().get(2));

        return new Rollbackward(point, tip);
    }

    @Override
    public DataItem serializeDI(Rollbackward object) {
        Array array = new Array();
        array.add(new UnsignedInteger(3));
        array.add(PointSerializer.INSTANCE.serializeDI(object.getPoint()));
        array.add(TipSerializer.INSTANCE.serializeDI(object.getTip()));
        return array;
    }
}
