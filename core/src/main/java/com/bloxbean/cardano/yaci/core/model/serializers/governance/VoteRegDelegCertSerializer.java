package com.bloxbean.cardano.yaci.core.model.serializers.governance;

import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.transaction.raw.CertKind;
import com.bloxbean.cardano.client.transaction.util.RawModels;
import com.bloxbean.cardano.yaci.core.model.certs.VoteRegDelegCert;
import com.bloxbean.cardano.yaci.core.model.serializers.CertificateSerializer;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;

public enum VoteRegDelegCertSerializer implements Serializer<VoteRegDelegCert> {
    INSTANCE;

    @Override
    public VoteRegDelegCert deserializeDI(DataItem di) {
        return (VoteRegDelegCert) CertificateSerializer.INSTANCE.read(CertKind.VOTE_REG_DELEG_CERT, RawModels.span(di));
    }
}
