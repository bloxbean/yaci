package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.exception.CborRuntimeException;
import com.bloxbean.cardano.yaci.core.model.certs.GenesisKeyDelegation;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;

import java.util.List;

import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.toHex;

public enum GenesisKeyDelegationSerializer implements Serializer<GenesisKeyDelegation> {
    INSTANCE;

    @Override
    public GenesisKeyDelegation deserializeDI(DataItem di) {
        Array genesisKeyDelegationArr = (Array) di;
        List<DataItem> dataItemList = genesisKeyDelegationArr.getDataItems();
        if (dataItemList == null || dataItemList.size() != 4) {
            throw new CborRuntimeException("GenesisKeyDelegation deserialization failed. Invalid number of DataItem(s) : "
                    + (dataItemList != null ? String.valueOf(dataItemList.size()) : null));
        }

        UnsignedInteger type = (UnsignedInteger) dataItemList.get(0);
        if (type == null || type.getValue().intValue() != 5)
            throw new CborRuntimeException("GenesisKeyDelegation deserialization failed. Invalid type : "
                    + type != null ? String.valueOf(type.getValue().intValue()) : null);

        String genesisHash = toHex(dataItemList.get(1));
        String genesisDelegateHash = toHex(dataItemList.get(2));
        String vrfKeyHash = toHex(dataItemList.get(3));

        return new GenesisKeyDelegation(genesisHash, genesisDelegateHash, vrfKeyHash);
    }
}
