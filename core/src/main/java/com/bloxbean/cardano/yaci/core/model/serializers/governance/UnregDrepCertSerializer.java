package com.bloxbean.cardano.yaci.core.model.serializers.governance;

import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.transaction.raw.CertKind;
import com.bloxbean.cardano.client.transaction.util.RawModels;
import com.bloxbean.cardano.yaci.core.model.certs.UnregDrepCert;
import com.bloxbean.cardano.yaci.core.model.serializers.CertificateSerializer;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;

public enum UnregDrepCertSerializer implements Serializer<UnregDrepCert> {
    INSTANCE;

    @Override
    public UnregDrepCert deserializeDI(DataItem di) {
        return (UnregDrepCert) CertificateSerializer.INSTANCE.read(CertKind.UNREG_DREP_CERT, RawModels.span(di));
    }
}
