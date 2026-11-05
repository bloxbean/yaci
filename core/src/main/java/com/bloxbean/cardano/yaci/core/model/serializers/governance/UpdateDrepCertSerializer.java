package com.bloxbean.cardano.yaci.core.model.serializers.governance;

import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.transaction.raw.CertKind;
import com.bloxbean.cardano.client.transaction.util.RawModels;
import com.bloxbean.cardano.yaci.core.model.certs.UpdateDrepCert;
import com.bloxbean.cardano.yaci.core.model.serializers.CertificateSerializer;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;

public enum UpdateDrepCertSerializer implements Serializer<UpdateDrepCert> {
    INSTANCE;

    @Override
    public UpdateDrepCert deserializeDI(DataItem di) {
        return (UpdateDrepCert) CertificateSerializer.INSTANCE.read(CertKind.UPDATE_DREP_CERT, RawModels.span(di));
    }
}
