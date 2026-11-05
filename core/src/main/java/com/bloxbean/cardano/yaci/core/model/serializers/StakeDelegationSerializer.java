package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.transaction.raw.CertKind;
import com.bloxbean.cardano.client.transaction.util.RawModels;
import com.bloxbean.cardano.yaci.core.model.certs.StakeDelegation;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;

public enum StakeDelegationSerializer implements Serializer<StakeDelegation> {
    INSTANCE;

    @Override
    public StakeDelegation deserializeDI(DataItem di) {
        return (StakeDelegation) CertificateSerializer.INSTANCE.read(CertKind.STAKE_DELEGATION, RawModels.span(di));
    }
}
