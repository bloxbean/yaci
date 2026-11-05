package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.transaction.raw.CertKind;
import com.bloxbean.cardano.client.transaction.util.RawModels;
import com.bloxbean.cardano.yaci.core.model.certs.StakeDeregistration;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;

public enum StakeDeregistrationSerializer implements Serializer<StakeDeregistration> {
    INSTANCE;

    /**
     * @deprecated A decoded certificate has lost its source encoding. Use {@link CertificateSerializer#read}.
     */
    @Deprecated(since = "0.5.0", forRemoval = true)
    @Override
    public StakeDeregistration deserializeDI(DataItem di) {
        return (StakeDeregistration) CertificateSerializer.INSTANCE.read(CertKind.STAKE_DEREGISTRATION, RawModels.span(di));
    }
}
