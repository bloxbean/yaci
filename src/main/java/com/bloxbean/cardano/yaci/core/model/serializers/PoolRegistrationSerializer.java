package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.yaci.core.model.certs.PoolRegistration;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;

import java.util.List;

public enum PoolRegistrationSerializer implements Serializer<PoolRegistration> {
    INSTANCE;

    @Override
    public PoolRegistration deserializeDI(DataItem di) {
        Array poolParamsArr = (Array) di;
        PoolRegistration poolRegistration = new PoolRegistration();

        List<DataItem> dataItemList = poolParamsArr.getDataItems(); //TODO

        return poolRegistration;
    }
}
