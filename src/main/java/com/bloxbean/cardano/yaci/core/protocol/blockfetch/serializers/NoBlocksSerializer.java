package com.bloxbean.cardano.yaci.core.protocol.blockfetch.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.messages.NoBlocks;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;

public enum NoBlocksSerializer implements Serializer<NoBlocks> {
    INSTANCE;

    @Override
    public byte[] serialize(NoBlocks noBlocks) {
        Array array = new Array();
        array.add(new UnsignedInteger(3));

        return CborSerializationUtil.serialize(array);
    }

    @Override
    public NoBlocks deserialize(byte[] bytes) {
        DataItem di = CborSerializationUtil.deserializeOne(bytes);

        if (di instanceof Array) {
            int key = ((UnsignedInteger)((Array) di).getDataItems().get(0)).getValue().intValue();
            if (key == 3)
                return new NoBlocks();
            else
                return null;
        } else
            return null;
    }
}
