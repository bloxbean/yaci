package com.bloxbean.cardano.yaci.core.protocol;

import co.nstant.in.cbor.model.DataItem;

public class DefaultSerializer implements Serializer {

    @Override
    public byte[] serialize(Object object) {
        return new byte[0];
    }

    @Override
    public Object deserialize(byte[] bytes) {
        return null;
    }

    @Override
    public Object deserializeDI(DataItem di) {
        return null;
    }
}
