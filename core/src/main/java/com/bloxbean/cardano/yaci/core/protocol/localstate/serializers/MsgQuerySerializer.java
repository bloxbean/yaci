package com.bloxbean.cardano.yaci.core.protocol.localstate.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.protocol.localstate.messages.MsgQuery;
import lombok.NonNull;

public enum MsgQuerySerializer implements Serializer<MsgQuery> {
    INSTANCE;

    @Override
    public DataItem serializeDI(@NonNull MsgQuery query) {
        Array array = new Array();
        array.add(new UnsignedInteger(3));
        array.add(query.getQuery().serialize());

        return array;
    }

    //TODO -- deserializeDI() is not required now as it's done in Server
}
