package com.bloxbean.cardano.yaci.core.protocol.blockfetch.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.messages.RequestRange;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.serializers.PointSerializer;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;

public enum RequestRangeSerializer implements Serializer<RequestRange> {
    INSTANCE;

    @Override
    public byte[] serialize(RequestRange range) {
        Array array = new Array();
        array.add(new UnsignedInteger(0));
        array.add(PointSerializer.INSTANCE.serializeDI(range.getFrom()));
        array.add(PointSerializer.INSTANCE.serializeDI(range.getTo()));

        byte[] bytes = CborSerializationUtil.serialize(array, false);
        return bytes;
    }

    @Override
    public RequestRange deserializeDI(DataItem di) {
        if (!(di instanceof Array)) {
            throw new IllegalArgumentException("Expected Array DataItem");
        }

        Array array = (Array) di;
        if (array.getDataItems().size() != 3) {
            throw new IllegalArgumentException("Expected array of size 3");
        }

        // Validate the first item is an UnsignedInteger with value 0
        if (!(array.getDataItems().get(0) instanceof UnsignedInteger) ||
                ((UnsignedInteger) array.getDataItems().get(0)).getValue().intValue() != 0) {
            throw new IllegalArgumentException("Expected UnsignedInteger with value 0 at index 0");
        }

        // Deserialize 'from' and 'to' points
        DataItem fromDI = array.getDataItems().get(1);
        DataItem toDI = array.getDataItems().get(2);
        return new RequestRange(
                PointSerializer.INSTANCE.deserializeDI(fromDI),
                PointSerializer.INSTANCE.deserializeDI(toDI)
        );

    }
}
