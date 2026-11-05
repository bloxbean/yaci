package com.bloxbean.cardano.yaci.core.model.serializers.governance;

import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.transaction.raw.CertKind;
import com.bloxbean.cardano.client.transaction.util.RawModels;
import com.bloxbean.cardano.yaci.core.model.certs.AuthCommitteeHotCert;
import com.bloxbean.cardano.yaci.core.model.serializers.CertificateSerializer;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;

public enum AuthCommitteeHotCertSerializer implements Serializer<AuthCommitteeHotCert> {
    INSTANCE;

    @Override
    public AuthCommitteeHotCert deserializeDI(DataItem di) {
        return (AuthCommitteeHotCert) CertificateSerializer.INSTANCE.read(CertKind.AUTH_COMMITTEE_HOT_CERT, RawModels.span(di));
    }
}
