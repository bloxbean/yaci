package com.bloxbean.cardano.yaci.core.model.serializers.governance;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredential;
import com.bloxbean.cardano.yaci.core.model.certs.StakeVoteDelegCert;
import com.bloxbean.cardano.yaci.core.model.governance.Drep;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;

import java.util.List;

import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.toHex;

/**
 * stake_vote_deleg_cert = (10, stake_credential, pool_keyhash, drep)
 */
public enum StakeVoteDelegCertSerializer implements Serializer<StakeVoteDelegCert> {
    INSTANCE;

    @Override
    public StakeVoteDelegCert deserializeDI(DataItem di) {
        Array certArray = (Array) di;
        List<DataItem> dataItemList = certArray.getDataItems();

        StakeCredential stakeCredential = StakeCredential.deserialize((Array) dataItemList.get(1));
        String poolKeyHash = toHex(dataItemList.get(2));
        Drep drep = DrepSerializer.INSTANCE.deserializeDI(dataItemList.get(3));
        return new StakeVoteDelegCert(stakeCredential, poolKeyHash, drep);
    }
}
