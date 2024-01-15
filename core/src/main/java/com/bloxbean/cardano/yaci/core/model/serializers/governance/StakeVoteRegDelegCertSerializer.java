package com.bloxbean.cardano.yaci.core.model.serializers.governance;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredential;
import com.bloxbean.cardano.yaci.core.model.certs.StakeVoteRegDelegCert;
import com.bloxbean.cardano.yaci.core.model.governance.Drep;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;

import java.math.BigInteger;
import java.util.List;

import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.toBigInteger;
import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.toHex;

/**
 * stake_vote_reg_deleg_cert = (13, stake_credential, pool_keyhash, drep, coin)
 */
public enum StakeVoteRegDelegCertSerializer implements Serializer<StakeVoteRegDelegCert> {
    INSTANCE;

    @Override
    public StakeVoteRegDelegCert deserializeDI(DataItem di) {
        Array certArray = (Array) di;
        List<DataItem> dataItemList = certArray.getDataItems();

        StakeCredential stakeCredential = StakeCredential.deserialize((Array) dataItemList.get(1));
        String poolKeyHash = toHex(dataItemList.get(2));
        Drep drep = DrepSerializer.INSTANCE.deserializeDI(dataItemList.get(3));
        BigInteger coin = toBigInteger(dataItemList.get(4));

        return new StakeVoteRegDelegCert(stakeCredential, poolKeyHash, drep, coin);
    }
}
