package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.exception.CborRuntimeException;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredential;
import com.bloxbean.cardano.yaci.core.model.certs.StakeRegistration;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;

import java.util.List;

public enum StakeRegistrationSerializer implements Serializer<StakeRegistration> {
    INSTANCE;

    @Override
    public StakeRegistration deserializeDI(DataItem stRegArrayDI) throws CborRuntimeException {
        Array stRegArray = (Array) stRegArrayDI;

        List<DataItem> dataItemList = stRegArray.getDataItems();
        if (dataItemList == null || dataItemList.size() != 2) {
            throw new CborRuntimeException("StakeRegistration deserialization failed. Invalid number of DataItem(s) : "
                    + (dataItemList != null ? String.valueOf(dataItemList.size()) : null));
        }

        UnsignedInteger type = (UnsignedInteger) dataItemList.get(0);
        if (type == null || type.getValue().intValue() != 0)
            throw new CborRuntimeException("StakeRegistration deserialization failed. Invalid type : "
                    + type != null ? String.valueOf(type.getValue().intValue()) : null);

        Array stakeCredArray = (Array) dataItemList.get(1);

        StakeCredential stakeCredential = StakeCredential.deserialize(stakeCredArray);

        return new StakeRegistration(stakeCredential);
    }
}
