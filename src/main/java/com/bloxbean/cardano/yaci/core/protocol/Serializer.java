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
        DataItem[] dataItems = CborSerializationUtil.deserialize(bytes);
        if (dataItems.length == 0)
            return null;

        if (dataItems.length == 1) {
            return deserializeDI(CborSerializationUtil.deserializeOne(bytes));
        } else {
            return deserializeDI(CborSerializationUtil.deserialize(bytes));
        }
    }

    default DataItem serializeDI(T object) {
        return null;
    }

    /**
     * Implement this method when only one top-level DataItem is expected after parsing
     * @param di
     * @return
     */
    default T deserializeDI(DataItem di) {
        throw new UnsupportedOperationException("This deserialization method is not implemented for this messge");
    }

    /**
     * Implement this method when multiple top-level DataItems are expected after parsing
     * @param dis
     * @return
     */
    default T deserializeDI(DataItem[] dis) {
        throw new UnsupportedOperationException("This deserialization method is not implemented for this message. Something is wrong");
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
