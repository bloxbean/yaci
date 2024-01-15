package com.bloxbean.cardano.yaci.core.model.serializers.governance;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.yaci.core.model.Credential;
import com.bloxbean.cardano.yaci.core.model.certs.AuthCommitteeHotCert;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;

import java.util.List;

/**
 * auth_committee_hot_cert = (14, committee_cold_credential, committee_hot_credential)
 */
public enum AuthCommitteeHotCertSerializer implements Serializer<AuthCommitteeHotCert> {
    INSTANCE;

    @Override
    public AuthCommitteeHotCert deserializeDI(DataItem di) {
        Array certArray = (Array) di;
        List<DataItem> dataItemList = certArray.getDataItems();

        Credential committeeColdCred = Credential.deserialize((Array) dataItemList.get(1));
        Credential committeeHotCred = Credential.deserialize((Array) dataItemList.get(2));

        return new AuthCommitteeHotCert(committeeColdCred, committeeHotCred);
    }
}
