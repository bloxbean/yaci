package com.bloxbean.cardano.yaci.core.model.serializers;

import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.transaction.raw.CertKind;
import com.bloxbean.cardano.yaci.core.model.certs.PoolRetirement;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;

public enum PoolRetirementSerializer implements Serializer<PoolRetirement> {
    INSTANCE;

    /**
     * @deprecated A decoded certificate has lost its source encoding. Use {@link CertificateSerializer#read}.
     */
    @Deprecated(since = "0.5.0", forRemoval = true)
    @Override
    public PoolRetirement deserializeDI(DataItem di) {
        return (PoolRetirement) CertificateSerializer.INSTANCE.read(CertKind.POOL_RETIREMENT, CborSerializationUtil.span(di));
    }
}
