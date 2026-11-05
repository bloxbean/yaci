package com.bloxbean.cardano.yaci.core.model.serializers.governance;

import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.transaction.raw.CertKind;
import com.bloxbean.cardano.client.transaction.util.RawModels;
import com.bloxbean.cardano.yaci.core.model.certs.StakeRegDelegCert;
import com.bloxbean.cardano.yaci.core.model.serializers.CertificateSerializer;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;

public enum StakeRegDelegCertSerializer implements Serializer<StakeRegDelegCert> {
    INSTANCE;

    @Override
    public StakeRegDelegCert deserializeDI(DataItem di) {
        return (StakeRegDelegCert) CertificateSerializer.INSTANCE.read(CertKind.STAKE_REG_DELEG_CERT, RawModels.span(di));
    }
}
