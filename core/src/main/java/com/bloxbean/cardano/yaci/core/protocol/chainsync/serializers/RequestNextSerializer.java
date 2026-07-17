package com.bloxbean.cardano.yaci.core.protocol.chainsync.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.RequestNext;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;

public enum RequestNextSerializer implements Serializer<RequestNext> {
    INSTANCE;

    public byte[] serialize(RequestNext requestNext) {
        Array array = new Array();
        array.add(new UnsignedInteger(0));
        return CborSerializationUtil.serialize(array, false);
    }

    @Override
    public RequestNext deserializeDI(DataItem di) {
        Array array = (Array) di;

        UnsignedInteger idx = (UnsignedInteger) array.getDataItems().get(0);
        if (idx.getValue().intValue() == 0) {
            return new RequestNext();
        }

        throw new IllegalArgumentException("Invalid DataItem for RequestNext deserialization");
    }
}
