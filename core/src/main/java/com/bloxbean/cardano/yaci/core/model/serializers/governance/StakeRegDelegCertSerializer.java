package com.bloxbean.cardano.yaci.core.model.serializers.governance;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredential;
import com.bloxbean.cardano.yaci.core.model.certs.StakeRegDelegCert;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;

import java.math.BigInteger;
import java.util.List;

import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.toBigInteger;
import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.toHex;

/**
 * stake_reg_deleg_cert = (11, stake_credential, pool_keyhash, coin)
 */
public enum StakeRegDelegCertSerializer implements Serializer<StakeRegDelegCert> {
    INSTANCE;

    @Override
    public StakeRegDelegCert deserializeDI(DataItem di) {
        Array certArray = (Array) di;
        List<DataItem> dataItemList = certArray.getDataItems();

        StakeCredential stakeCredential = StakeCredential.deserialize((Array) dataItemList.get(1));
        String poolKeyHash = toHex(dataItemList.get(2));
        BigInteger coin = toBigInteger(dataItemList.get(3));

        return new StakeRegDelegCert(stakeCredential, poolKeyHash, coin);
    }
}
