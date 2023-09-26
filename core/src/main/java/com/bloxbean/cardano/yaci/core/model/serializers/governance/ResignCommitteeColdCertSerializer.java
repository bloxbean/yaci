package com.bloxbean.cardano.yaci.core.model.serializers.governance;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.yaci.core.model.Credential;
import com.bloxbean.cardano.yaci.core.model.certs.ResignCommitteeColdCert;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;

import java.util.List;

/**
 * resign_committee_cold_cert = (15, committee_cold_credential)
 */
public enum ResignCommitteeColdCertSerializer implements Serializer<ResignCommitteeColdCert> {
    INSTANCE;

    @Override
    public ResignCommitteeColdCert deserializeDI(DataItem di) {
        Array certArray = (Array) di;
        List<DataItem> dataItemList = certArray.getDataItems();

        Credential committeeColdCred = Credential.deserialize((Array) dataItemList.get(1));
        return new ResignCommitteeColdCert(committeeColdCred);
    }
}
