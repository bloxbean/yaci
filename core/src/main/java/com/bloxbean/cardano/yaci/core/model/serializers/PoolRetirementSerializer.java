package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.transaction.raw.CertKind;
import com.bloxbean.cardano.client.transaction.util.RawModels;
import com.bloxbean.cardano.yaci.core.model.certs.PoolRetirement;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;

public enum PoolRetirementSerializer implements Serializer<PoolRetirement> {
    INSTANCE;

    @Override
    public PoolRetirement deserializeDI(DataItem di) {
        return (PoolRetirement) CertificateSerializer.INSTANCE.read(CertKind.POOL_RETIREMENT, RawModels.span(di));
    }
}
