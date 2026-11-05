package com.bloxbean.cardano.yaci.core.model.serializers.governance;

import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.transaction.raw.CertKind;
import com.bloxbean.cardano.client.transaction.util.RawModels;
import com.bloxbean.cardano.yaci.core.model.certs.UnregCert;
import com.bloxbean.cardano.yaci.core.model.serializers.CertificateSerializer;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;

public enum UnregCertSerializer implements Serializer<UnregCert> {
    INSTANCE;

    @Override
    public UnregCert deserializeDI(DataItem di) {
        return (UnregCert) CertificateSerializer.INSTANCE.read(CertKind.UNREG_CERT, RawModels.span(di));
    }
}
