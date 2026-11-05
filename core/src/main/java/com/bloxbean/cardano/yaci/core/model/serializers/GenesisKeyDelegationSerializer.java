package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.client.transaction.raw.CertKind;
import com.bloxbean.cardano.client.transaction.util.RawModels;
import com.bloxbean.cardano.yaci.core.model.certs.GenesisKeyDelegation;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;

public enum GenesisKeyDelegationSerializer implements Serializer<GenesisKeyDelegation> {
    INSTANCE;

    /**
     * @deprecated A decoded certificate has lost its source encoding. Use {@link CertificateSerializer#read}.
     */
    @Deprecated(since = "0.5.0", forRemoval = true)
    @Override
    public GenesisKeyDelegation deserializeDI(DataItem di) {
        return read(RawModels.span(di));
    }

    GenesisKeyDelegation read(CborSpan span) {
        return (GenesisKeyDelegation) CertificateSerializer.INSTANCE.read(CertKind.GENESIS_KEY_DELEGATION, span);
    }
}
