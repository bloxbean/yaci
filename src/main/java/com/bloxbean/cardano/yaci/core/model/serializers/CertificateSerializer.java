package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.exception.CborRuntimeException;
import com.bloxbean.cardano.yaci.core.model.certs.Certificate;
import com.bloxbean.cardano.yaci.core.model.certs.GenesisKeyDelegation;
import com.bloxbean.cardano.yaci.core.model.certs.MoveInstataneous;
import com.bloxbean.cardano.yaci.core.model.certs.PoolRetirement;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;

import java.util.List;
import java.util.Objects;

public enum CertificateSerializer implements Serializer<Certificate> {
    INSTANCE;

    @Override
    public Certificate deserializeDI(DataItem certArrayDI) {
        Array certArray = (Array)certArrayDI;
        Objects.requireNonNull(certArray);

        List<DataItem> dataItemList = certArray.getDataItems();
        if (dataItemList == null || dataItemList.size() < 2) {
            throw new CborRuntimeException("Certificate deserialization failed. Invalid number of DataItem(s) : "
                    + (dataItemList != null ? String.valueOf(dataItemList.size()) : null));
        }

        UnsignedInteger typeUI = (UnsignedInteger) dataItemList.get(0);
        int type = typeUI.getValue().intValue();

        Certificate certificate;
        switch (type) {
            case 0:
                certificate = StakeRegistrationSerializer.INSTANCE.deserializeDI(certArray);
                break;
            case 1:
                certificate = StakeDeregistrationSerializer.INSTANCE.deserializeDI(certArray);
                break;
            case 2:
                certificate = StakeDelegationSerializer.INSTANCE.deserializeDI(certArray);
                break;
            case 3:
                certificate = PoolRegistrationSerializer.INSTANCE.deserializeDI(certArray);
                break;
            case 4:
                //Pool retirement
                certificate = new PoolRetirement();
                break;
            case 5:
                //Genesis key delegation
                certificate = new GenesisKeyDelegation();
                break;
            case 6:
                //Move instateneous rewards certs
                certificate = new MoveInstataneous();
                break;
            default:
                throw new CborRuntimeException("Certificate deserialization failed. Unknown type : " + type);
        }

        return certificate;
    }
}
