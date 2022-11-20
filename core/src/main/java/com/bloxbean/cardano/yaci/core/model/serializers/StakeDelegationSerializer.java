package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.exception.CborRuntimeException;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredential;
import com.bloxbean.cardano.yaci.core.model.certs.StakeDelegation;
import com.bloxbean.cardano.yaci.core.model.certs.StakePoolId;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;

import java.util.List;

public enum StakeDelegationSerializer implements Serializer<StakeDelegation> {
    INSTANCE;

    @Override
    public StakeDelegation deserializeDI(DataItem stRegArrayDI) {
        Array stRegArray = (Array) stRegArrayDI;

        List<DataItem> dataItemList = stRegArray.getDataItems();
        if (dataItemList == null || dataItemList.size() != 3) {
            throw new CborRuntimeException("StakeDelegation deserialization failed. Invalid number of DataItem(s) : "
                    + (dataItemList != null ? String.valueOf(dataItemList.size()) : null));
        }

        UnsignedInteger type = (UnsignedInteger) dataItemList.get(0);
        if (type == null || type.getValue().intValue() != 2)
            throw new CborRuntimeException("StakeRegistration deserialization failed. Invalid type : "
                    + type != null ? String.valueOf(type.getValue().intValue()) : null);

        Array stakeCredArray = (Array) dataItemList.get(1);
        StakeCredential stakeCredential = StakeCredential.deserialize(stakeCredArray);

        ByteString poolKeyHashDI = (ByteString) dataItemList.get(2);
        StakePoolId stakePoolId = new StakePoolId(poolKeyHashDI.getBytes());

        return new StakeDelegation(stakeCredential, stakePoolId);
    }
}
