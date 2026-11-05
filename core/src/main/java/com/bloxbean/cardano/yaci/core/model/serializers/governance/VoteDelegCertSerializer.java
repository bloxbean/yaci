package com.bloxbean.cardano.yaci.core.model.serializers.governance;

import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.transaction.raw.CertKind;
import com.bloxbean.cardano.client.transaction.util.RawModels;
import com.bloxbean.cardano.yaci.core.model.certs.VoteDelegCert;
import com.bloxbean.cardano.yaci.core.model.serializers.CertificateSerializer;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;

public enum VoteDelegCertSerializer implements Serializer<VoteDelegCert> {
    INSTANCE;

    /**
     * @deprecated A decoded certificate has lost its source encoding. Use {@link CertificateSerializer#read}.
     */
    @Deprecated(since = "0.5.0", forRemoval = true)
    @Override
    public VoteDelegCert deserializeDI(DataItem di) {
        return (VoteDelegCert) CertificateSerializer.INSTANCE.read(CertKind.VOTE_DELEG_CERT, RawModels.span(di));
    }
}
