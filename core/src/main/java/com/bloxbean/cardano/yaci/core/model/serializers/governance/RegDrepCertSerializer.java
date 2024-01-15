package com.bloxbean.cardano.yaci.core.model.serializers.governance;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.yaci.core.model.Credential;
import com.bloxbean.cardano.yaci.core.model.certs.RegDrepCert;
import com.bloxbean.cardano.yaci.core.model.governance.Anchor;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;

import java.math.BigInteger;
import java.util.List;

import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.toBigInteger;

/**
 * reg_drep_cert = (16, drep_credential, coin, anchor / null)
 */
public enum RegDrepCertSerializer implements Serializer<RegDrepCert> {
    INSTANCE;

    @Override
    public RegDrepCert deserializeDI(DataItem di) {
        Array certArray = (Array) di;
        List<DataItem> dataItemList = certArray.getDataItems();

        Credential drepCred = Credential.deserialize((Array) dataItemList.get(1));
        BigInteger coin = toBigInteger(dataItemList.get(2));
        Anchor anchor = AnchorSerializer.INSTANCE.deserializeDI(dataItemList.get(3));
        return new RegDrepCert(drepCred, coin, anchor);
    }
}
