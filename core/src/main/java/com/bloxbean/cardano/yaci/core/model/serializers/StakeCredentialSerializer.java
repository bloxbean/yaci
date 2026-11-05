package com.bloxbean.cardano.yaci.core.model.serializers;

import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.transaction.raw.RawCredential;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredential;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;

public enum StakeCredentialSerializer implements Serializer<StakeCredential> {
    INSTANCE;

    /**
     * @deprecated A decoded credential has lost its source encoding. Use
     * {@link CertificateSerializer#stakeCredential(RawCredential)}.
     */
    @Deprecated(since = "0.5.0", forRemoval = true)
    public StakeCredential deserializeDI(DataItem stakeCredArrayDI)  {
        return CertificateSerializer.stakeCredential(RawCredential.of(CborSerializationUtil.span(stakeCredArrayDI)));
    }
}
