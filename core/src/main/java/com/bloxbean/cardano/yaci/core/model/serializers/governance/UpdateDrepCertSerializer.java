package com.bloxbean.cardano.yaci.core.model.serializers.governance;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.yaci.core.model.Credential;
import com.bloxbean.cardano.yaci.core.model.certs.UpdateDrepCert;
import com.bloxbean.cardano.yaci.core.model.governance.Anchor;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;

import java.util.List;

/**
 * update_drep_cert = (18, drep_credential, anchor / null)
 */
public enum UpdateDrepCertSerializer implements Serializer<UpdateDrepCert> {
    INSTANCE;

    @Override
    public UpdateDrepCert deserializeDI(DataItem di) {
        Array certArray = (Array) di;
        List<DataItem> dataItemList = certArray.getDataItems();

        Credential drepCred = Credential.deserialize((Array) dataItemList.get(1));
        Anchor anchor = AnchorSerializer.INSTANCE.deserializeDI(dataItemList.get(2));
        return new UpdateDrepCert(drepCred, anchor);
    }
}
