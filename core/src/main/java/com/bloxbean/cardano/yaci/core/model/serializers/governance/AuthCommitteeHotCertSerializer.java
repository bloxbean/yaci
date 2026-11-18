package com.bloxbean.cardano.yaci.core.model.serializers.governance;

import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.transaction.raw.CertKind;
import com.bloxbean.cardano.yaci.core.model.certs.AuthCommitteeHotCert;
import com.bloxbean.cardano.yaci.core.model.serializers.CertificateSerializer;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;

public enum AuthCommitteeHotCertSerializer implements Serializer<AuthCommitteeHotCert> {
    INSTANCE;

    /**
     * @deprecated A decoded certificate has lost its source encoding. Use {@link CertificateSerializer#read}.
     */
    @Deprecated(since = "0.5.0", forRemoval = true)
    @Override
    public AuthCommitteeHotCert deserializeDI(DataItem di) {
        return (AuthCommitteeHotCert) CertificateSerializer.INSTANCE.read(CertKind.AUTH_COMMITTEE_HOT_CERT, CborSerializationUtil.span(di));
    }
}
