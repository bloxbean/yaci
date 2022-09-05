package com.bloxbean.cardano.yaci.core.protocol.localstate.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.serializers.PointSerializer;
import com.bloxbean.cardano.yaci.core.protocol.localstate.messages.MsgAcquire;
import lombok.NonNull;

public enum MsgAcquireSerializer implements Serializer<MsgAcquire> {
    INSTANCE;

    @Override
    public DataItem serializeDI(@NonNull MsgAcquire msgAcquire) {
        Array array = new Array();
        if (msgAcquire.getPoint() != null) {
            array.add(new UnsignedInteger(0));
            array.add(PointSerializer.INSTANCE.serializeDI(msgAcquire.getPoint()));
        } else {
            array.add(new UnsignedInteger(8));
        }

        return array;
    }

    //TODO -- deserializeDI() is not required now as it's done in Server
}
