package com.bloxbean.cardano.yaci.core.model.serializers;

import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.transaction.raw.CertKind;
import com.bloxbean.cardano.yaci.core.model.certs.StakeDelegation;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;

public enum StakeDelegationSerializer implements Serializer<StakeDelegation> {
    INSTANCE;

    /**
     * @deprecated A decoded certificate has lost its source encoding. Use {@link CertificateSerializer#read}.
     */
    @Deprecated(since = "0.5.0", forRemoval = true)
    @Override
    public StakeDelegation deserializeDI(DataItem di) {
        return (StakeDelegation) CertificateSerializer.INSTANCE.read(CertKind.STAKE_DELEGATION, CborSerializationUtil.span(di));
    }
}
