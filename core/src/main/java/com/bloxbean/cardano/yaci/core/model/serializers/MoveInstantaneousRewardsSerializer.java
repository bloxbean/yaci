package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.transaction.raw.CertKind;
import com.bloxbean.cardano.client.transaction.util.RawModels;
import com.bloxbean.cardano.yaci.core.model.certs.MoveInstataneous;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;

public enum MoveInstantaneousRewardsSerializer implements Serializer<MoveInstataneous> {
    INSTANCE;

    @Override
    public MoveInstataneous deserializeDI(DataItem di) {
        return (MoveInstataneous) CertificateSerializer.INSTANCE.read(CertKind.MOVE_INSTANTANEOUS_REWARDS, RawModels.span(di));
    }
}
