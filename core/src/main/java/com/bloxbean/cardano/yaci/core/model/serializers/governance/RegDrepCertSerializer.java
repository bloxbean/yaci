package com.bloxbean.cardano.yaci.core.model.serializers.governance;

import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.transaction.raw.CertKind;
import com.bloxbean.cardano.yaci.core.model.certs.RegDrepCert;
import com.bloxbean.cardano.yaci.core.model.serializers.CertificateSerializer;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;

public enum RegDrepCertSerializer implements Serializer<RegDrepCert> {
    INSTANCE;

    /**
     * @deprecated A decoded certificate has lost its source encoding. Use {@link CertificateSerializer#read}.
     */
    @Deprecated(since = "0.5.0", forRemoval = true)
    @Override
    public RegDrepCert deserializeDI(DataItem di) {
        return (RegDrepCert) CertificateSerializer.INSTANCE.read(CertKind.REG_DREP_CERT, CborSerializationUtil.span(di));
    }
}
