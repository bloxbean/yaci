package com.bloxbean.cardano.yaci.core.protocol.localstate.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.serializers.PointSerializer;
import com.bloxbean.cardano.yaci.core.protocol.localstate.messages.MsgReAcquire;
import lombok.NonNull;

public enum MsgReAcquireSerializer implements Serializer<MsgReAcquire> {
    INSTANCE;

    @Override
    public DataItem serializeDI(@NonNull MsgReAcquire msgReAcquire) {
        Array array = new Array();
        if (msgReAcquire.getPoint() != null) {
            array.add(new UnsignedInteger(6));
            array.add(PointSerializer.INSTANCE.serializeDI(msgReAcquire.getPoint()));
        } else {
            array.add(new UnsignedInteger(9));
        }

        return array;
    }

    //TODO -- deserializeDI() is not required now as it's done in Server
}
