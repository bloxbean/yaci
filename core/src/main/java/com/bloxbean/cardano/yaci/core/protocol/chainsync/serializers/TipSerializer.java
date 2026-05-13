package com.bloxbean.cardano.yaci.core.protocol.chainsync.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;

public enum TipSerializer implements Serializer<Tip> {
    INSTANCE;

    public Tip deserializeDI(DataItem di) {
        Array array = (Array) di;
        DataItem pointDI = array.getDataItems().get(0);
        Point tipPoint = PointSerializer.INSTANCE.deserializeDI(pointDI);

        long block = ((UnsignedInteger) array.getDataItems().get(1)).getValue().longValue();

        return new Tip(tipPoint, block);
    }

    @Override
    public DataItem serializeDI(Tip tip) {
        Array array = new Array();
        array.add(PointSerializer.INSTANCE.serializeDI(tip.getPoint()));
        array.add(new UnsignedInteger(tip.getBlock()));
        return array;
    }
}
