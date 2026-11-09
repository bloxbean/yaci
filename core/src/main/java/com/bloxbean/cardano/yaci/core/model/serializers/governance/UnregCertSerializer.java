package com.bloxbean.cardano.yaci.core.model.serializers.governance;

import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.transaction.raw.CertKind;
import com.bloxbean.cardano.yaci.core.model.certs.UnregCert;
import com.bloxbean.cardano.yaci.core.model.serializers.CertificateSerializer;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;

public enum UnregCertSerializer implements Serializer<UnregCert> {
    INSTANCE;

    /**
     * @deprecated A decoded certificate has lost its source encoding. Use {@link CertificateSerializer#read}.
     */
    @Deprecated(since = "0.5.0", forRemoval = true)
    @Override
    public UnregCert deserializeDI(DataItem di) {
        return (UnregCert) CertificateSerializer.INSTANCE.read(CertKind.UNREG_CERT, CborSerializationUtil.span(di));
    }
}
