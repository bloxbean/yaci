package com.bloxbean.cardano.yaci.core.model.serializers.governance;

import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.transaction.raw.CertKind;
import com.bloxbean.cardano.client.transaction.util.RawModels;
import com.bloxbean.cardano.yaci.core.model.certs.StakeVoteRegDelegCert;
import com.bloxbean.cardano.yaci.core.model.serializers.CertificateSerializer;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;

public enum StakeVoteRegDelegCertSerializer implements Serializer<StakeVoteRegDelegCert> {
    INSTANCE;

    /**
     * @deprecated A decoded certificate has lost its source encoding. Use {@link CertificateSerializer#read}.
     */
    @Deprecated(since = "0.5.0", forRemoval = true)
    @Override
    public StakeVoteRegDelegCert deserializeDI(DataItem di) {
        return (StakeVoteRegDelegCert) CertificateSerializer.INSTANCE.read(CertKind.STAKE_VOTE_REG_DELEG_CERT, RawModels.span(di));
    }
}
