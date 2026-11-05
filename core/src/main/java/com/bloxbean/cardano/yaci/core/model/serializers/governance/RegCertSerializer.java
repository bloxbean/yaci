package com.bloxbean.cardano.yaci.core.model.serializers.governance;

import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.transaction.raw.CertKind;
import com.bloxbean.cardano.client.transaction.util.RawModels;
import com.bloxbean.cardano.yaci.core.model.certs.RegCert;
import com.bloxbean.cardano.yaci.core.model.serializers.CertificateSerializer;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;

public enum RegCertSerializer implements Serializer<RegCert> {
    INSTANCE;

    @Override
    public RegCert deserializeDI(DataItem di) {
        return (RegCert) CertificateSerializer.INSTANCE.read(CertKind.REG_CERT, RawModels.span(di));
    }
}
