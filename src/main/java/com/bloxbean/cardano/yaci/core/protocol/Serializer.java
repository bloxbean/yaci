package com.bloxbean.cardano.yaci.core.protocol;

import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;

public interface Serializer<T> {
    default byte[] serialize(T object) {
        return new byte[0];
    }

    default T deserialize(byte[] bytes) {
        DataItem di = CborSerializationUtil.deserialize(bytes);
        return deserializeDI(di);
    }

    default DataItem serializeDI(T object) {
        return null;
    }

    default T deserializeDI(DataItem di) {
        return null;
    }
}
