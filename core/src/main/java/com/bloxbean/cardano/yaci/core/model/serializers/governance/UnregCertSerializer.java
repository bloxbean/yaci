package com.bloxbean.cardano.yaci.core.model.serializers.governance;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredential;
import com.bloxbean.cardano.yaci.core.model.certs.UnregCert;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;

import java.math.BigInteger;
import java.util.List;

import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.toBigInteger;

/**
 * unreg_cert = (8, stake_credential, coin)
 */
public enum UnregCertSerializer implements Serializer<UnregCert> {
    INSTANCE;

    @Override
    public UnregCert deserializeDI(DataItem di) {
        Array certArray = (Array)di;
        List<DataItem> dataItemList = certArray.getDataItems();

        StakeCredential stakeCredential = StakeCredential.deserialize((Array) dataItemList.get(1));
        BigInteger coin = toBigInteger(dataItemList.get(2));

        return new UnregCert(stakeCredential, coin);
    }
}
