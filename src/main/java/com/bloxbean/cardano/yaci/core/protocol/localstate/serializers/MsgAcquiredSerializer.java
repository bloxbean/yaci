package com.bloxbean.cardano.yaci.core.protocol.localstate.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.protocol.localstate.messages.MsgAcquired;

public enum MsgAcquiredSerializer implements Serializer<MsgAcquired> {
    INSTANCE;

    @Override
    public MsgAcquired deserializeDI(DataItem di) {
        Array array = (Array) di;
        int key = ((UnsignedInteger)array.getDataItems().get(0)).getValue().intValue();
        if (key != 1)
            return null;
        else
            return new MsgAcquired();
    }

    //TODO -- serialize() is not required now as it's used in server
}
