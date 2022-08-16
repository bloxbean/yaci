package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.exception.CborRuntimeException;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredential;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;

import java.math.BigInteger;
import java.util.List;

public enum StakeCredentialSerializer implements Serializer<StakeCredential> {
    INSTANCE;

    public StakeCredential deserializeDI(DataItem stakeCredArrayDI)  {
        Array stakeCredArray = (Array) stakeCredArrayDI;
        List<DataItem> dataItemList = stakeCredArray.getDataItems();
        if (dataItemList == null || dataItemList.size() != 2)
            throw new CborRuntimeException("StakeCredential deserialization failed. Invalid number of DataItem(s) : "
                    + (dataItemList != null ? String.valueOf(dataItemList.size()) : null));

        UnsignedInteger typeDI = (UnsignedInteger) dataItemList.get(0);
        ByteString hashDI = (ByteString) dataItemList.get(1);

        BigInteger typeBI = typeDI.getValue();
        if (typeBI.intValue() == 0) {
            return StakeCredential.fromKeyHash(hashDI.getBytes());
        } else if (typeBI.intValue() == 1) {
            return StakeCredential.fromScriptHash(hashDI.getBytes());
        } else {
            throw new CborRuntimeException("StakeCredential deserialization failed. Invalid StakeCredType : "
                    + typeBI.intValue());
        }
    }
}
