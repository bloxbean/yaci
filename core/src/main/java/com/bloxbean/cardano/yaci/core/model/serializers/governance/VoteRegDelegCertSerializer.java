package com.bloxbean.cardano.yaci.core.model.serializers.governance;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredential;
import com.bloxbean.cardano.yaci.core.model.certs.VoteRegDelegCert;
import com.bloxbean.cardano.yaci.core.model.governance.Drep;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;

import java.math.BigInteger;
import java.util.List;

import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.toBigInteger;

/**
 * vote_reg_deleg_cert = (12, stake_credential, drep, coin)
 */
public enum VoteRegDelegCertSerializer implements Serializer<VoteRegDelegCert> {
    INSTANCE;

    @Override
    public VoteRegDelegCert deserializeDI(DataItem di) {
        Array certArray = (Array) di;
        List<DataItem> dataItemList = certArray.getDataItems();

        StakeCredential stakeCredential = StakeCredential.deserialize((Array) dataItemList.get(1));
        Drep drep = DrepSerializer.INSTANCE.deserializeDI(dataItemList.get(2));
        BigInteger coin = toBigInteger(dataItemList.get(3));

        return new VoteRegDelegCert(stakeCredential, drep, coin);
    }
}
