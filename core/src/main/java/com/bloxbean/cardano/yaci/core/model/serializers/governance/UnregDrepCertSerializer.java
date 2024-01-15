package com.bloxbean.cardano.yaci.core.model.serializers.governance;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.yaci.core.model.Credential;
import com.bloxbean.cardano.yaci.core.model.certs.UnregDrepCert;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;

import java.math.BigInteger;
import java.util.List;

import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.toBigInteger;

/**
 * unreg_drep_cert = (17, drep_credential, coin)
 */
public enum UnregDrepCertSerializer implements Serializer<UnregDrepCert> {
    INSTANCE;

    @Override
    public UnregDrepCert deserializeDI(DataItem di) {
        Array certArray = (Array) di;
        List<DataItem> dataItemList = certArray.getDataItems();

        Credential drepCred = Credential.deserialize((Array) dataItemList.get(1));
        BigInteger coin = toBigInteger(dataItemList.get(2));
        return new UnregDrepCert(drepCred, coin);
    }
}
