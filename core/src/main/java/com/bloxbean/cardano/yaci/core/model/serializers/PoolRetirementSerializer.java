package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.exception.CborRuntimeException;
import com.bloxbean.cardano.yaci.core.model.certs.PoolRetirement;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;

import java.util.List;

import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.toHex;
import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.toLong;

public enum PoolRetirementSerializer implements Serializer<PoolRetirement> {
    INSTANCE;

    @Override
    public PoolRetirement deserializeDI(DataItem di) {
        Array poolRetirementsArr = (Array) di;
        List<DataItem> dataItemList = poolRetirementsArr.getDataItems();
        if (dataItemList == null || dataItemList.size() != 3) {
            throw new CborRuntimeException("PoolRetirement deserialization failed. Invalid number of DataItem(s) : "
                    + (dataItemList != null ? String.valueOf(dataItemList.size()) : null));
        }

        UnsignedInteger type = (UnsignedInteger) dataItemList.get(0);
        if (type == null || type.getValue().intValue() != 4)
            throw new CborRuntimeException("PoolRetirement deserialization failed. Invalid type : "
                    + type != null ? String.valueOf(type.getValue().intValue()) : null);

        String poolKeyHash = toHex(dataItemList.get(1));
        long epoch = toLong(dataItemList.get(2));

        return new PoolRetirement(poolKeyHash, epoch);
    }
}
