package com.bloxbean.cardano.yaci.core.protocol.localstate.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.protocol.localstate.messages.MsgRelease;
import lombok.NonNull;

public enum MsgReleaseSerializer implements Serializer<MsgRelease> {
    INSTANCE;

    @Override
    public DataItem serializeDI(@NonNull MsgRelease release) {
        Array array = new Array();
        array.add(new UnsignedInteger(5));

        return array;
    }

    //TODO -- deserializeDI() is not required now as it's done in Server
}
