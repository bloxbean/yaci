package com.bloxbean.cardano.yaci.core.model.serializers.governance;

import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.transaction.raw.CertKind;
import com.bloxbean.cardano.yaci.core.model.certs.UnregDrepCert;
import com.bloxbean.cardano.yaci.core.model.serializers.CertificateSerializer;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;

public enum UnregDrepCertSerializer implements Serializer<UnregDrepCert> {
    INSTANCE;

    /**
     * @deprecated A decoded certificate has lost its source encoding. Use {@link CertificateSerializer#read}.
     */
    @Deprecated(since = "0.5.0", forRemoval = true)
    @Override
    public UnregDrepCert deserializeDI(DataItem di) {
        return (UnregDrepCert) CertificateSerializer.INSTANCE.read(CertKind.UNREG_DREP_CERT, CborSerializationUtil.span(di));
    }
}
