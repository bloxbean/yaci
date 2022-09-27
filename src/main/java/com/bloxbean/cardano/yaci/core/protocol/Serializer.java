package com.bloxbean.cardano.yaci.core.protocol;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;

import java.util.List;

public interface Serializer<T> {
    default byte[] serialize(T object) {
        DataItem di = serializeDI(object);
        return CborSerializationUtil.serialize(di);
    }

    default T deserialize(byte[] bytes) {
        DataItem di = CborSerializationUtil.deserializeOne(bytes);
        return deserializeDI(di);
    }

    default DataItem serializeDI(T object) {
        return null;
    }

    default T deserializeDI(DataItem di) {
        return null;
    }

    /**
     * Check if the msg type is correct by checking the first element in the array.
     *
     * @param di
     * @param expectedVal
     * @return DataItem list
     */
    default List<DataItem> checkMsgType(DataItem di, int expectedVal) {
        List<DataItem> dataItemList = ((Array) di).getDataItems();
        int key = ((UnsignedInteger)dataItemList.get(0)).getValue().intValue();
        if (key != expectedVal)
            throw new IllegalStateException("Invalid key. Expected : " + expectedVal + " Found: " + key);

        return dataItemList;
    }
}
