package com.bloxbean.cardano.yaci.core.model.serializers.governance;

import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.transaction.raw.CertKind;
import com.bloxbean.cardano.client.transaction.util.RawModels;
import com.bloxbean.cardano.yaci.core.model.certs.RegDrepCert;
import com.bloxbean.cardano.yaci.core.model.serializers.CertificateSerializer;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;

public enum RegDrepCertSerializer implements Serializer<RegDrepCert> {
    INSTANCE;

    @Override
    public RegDrepCert deserializeDI(DataItem di) {
        return (RegDrepCert) CertificateSerializer.INSTANCE.read(CertKind.REG_DREP_CERT, RawModels.span(di));
    }
}
