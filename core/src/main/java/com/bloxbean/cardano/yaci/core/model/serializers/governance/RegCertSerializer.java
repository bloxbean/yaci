package com.bloxbean.cardano.yaci.core.model.serializers.governance;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.yaci.core.model.certs.RegCert;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredential;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;

import java.math.BigInteger;
import java.util.List;

import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.toBigInteger;

/**
 * reg_cert = (7, stake_credential, coin)
 */
public enum RegCertSerializer implements Serializer<RegCert> {
    INSTANCE;

    @Override
    public RegCert deserializeDI(DataItem di) {
        Array certArray = (Array)di;
        List<DataItem> dataItemList = certArray.getDataItems();

        StakeCredential stakeCredential = StakeCredential.deserialize((Array) dataItemList.get(1));
        BigInteger coin = toBigInteger(dataItemList.get(2));

        return new RegCert(stakeCredential, coin);
    }
}
