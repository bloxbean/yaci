package com.bloxbean.cardano.yaci.core.model.serializers.governance;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredential;
import com.bloxbean.cardano.yaci.core.model.certs.VoteDelegCert;
import com.bloxbean.cardano.yaci.core.model.governance.Drep;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;

import java.util.List;

/**
 * vote_deleg_cert = (9, stake_credential, drep)
 */
public enum VoteDelegCertSerializer implements Serializer<VoteDelegCert> {
    INSTANCE;

    @Override
    public VoteDelegCert deserializeDI(DataItem di) {
        Array certArray = (Array) di;
        List<DataItem> dataItemList = certArray.getDataItems();

        StakeCredential stakeCredential = StakeCredential.deserialize((Array) dataItemList.get(1));
        Drep drep = DrepSerializer.INSTANCE.deserializeDI(dataItemList.get(2));
        return new VoteDelegCert(stakeCredential, drep);
    }
}
