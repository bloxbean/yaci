package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.transaction.raw.CertKind;
import com.bloxbean.cardano.client.transaction.util.RawModels;
import com.bloxbean.cardano.yaci.core.model.certs.StakeRegistration;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;

public enum StakeRegistrationSerializer implements Serializer<StakeRegistration> {
    INSTANCE;

    @Override
    public StakeRegistration deserializeDI(DataItem di) {
        return (StakeRegistration) CertificateSerializer.INSTANCE.read(CertKind.STAKE_REGISTRATION, RawModels.span(di));
    }
}
