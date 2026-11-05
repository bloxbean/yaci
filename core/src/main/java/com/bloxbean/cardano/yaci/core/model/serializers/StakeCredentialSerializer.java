package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.transaction.raw.RawCredential;
import com.bloxbean.cardano.client.transaction.util.RawModels;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredential;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;

public enum StakeCredentialSerializer implements Serializer<StakeCredential> {
    INSTANCE;

    public StakeCredential deserializeDI(DataItem stakeCredArrayDI)  {
        return CertificateSerializer.stakeCredential(RawCredential.of(RawModels.span(stakeCredArrayDI)));
    }
}
