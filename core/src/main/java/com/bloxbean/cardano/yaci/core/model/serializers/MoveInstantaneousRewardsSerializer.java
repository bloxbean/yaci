package com.bloxbean.cardano.yaci.core.model.serializers;

import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.transaction.raw.CertKind;
import com.bloxbean.cardano.yaci.core.model.certs.MoveInstataneous;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;

public enum MoveInstantaneousRewardsSerializer implements Serializer<MoveInstataneous> {
    INSTANCE;

    /**
     * @deprecated A decoded certificate has lost its source encoding. Use {@link CertificateSerializer#read}.
     */
    @Deprecated(since = "0.5.0", forRemoval = true)
    @Override
    public MoveInstataneous deserializeDI(DataItem di) {
        return (MoveInstataneous) CertificateSerializer.INSTANCE.read(CertKind.MOVE_INSTANTANEOUS_REWARDS, CborSerializationUtil.span(di));
    }
}
