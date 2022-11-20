package com.bloxbean.cardano.yaci.core.protocol.blockfetch.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.messages.ClientDone;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;

public enum ClientDoneSerializer implements Serializer<ClientDone> {
    INSTANCE;

    @Override
    public byte[] serialize(ClientDone object) {
        Array array = new Array();
        array.add(new UnsignedInteger(1));

        return CborSerializationUtil.serialize(array);
    }

    @Override
    public ClientDone deserialize(byte[] bytes) {
        DataItem di = CborSerializationUtil.deserializeOne(bytes);

        if (di instanceof Array) {
            int key = ((UnsignedInteger)((Array) di).getDataItems().get(0)).getValue().intValue();
            if (key == 1)
                return new ClientDone();
            else
                return null;
        } else
            return null;
    }
}
