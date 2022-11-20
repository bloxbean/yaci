package com.bloxbean.cardano.yaci.core.protocol.blockfetch.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.messages.MsgBlock;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;

public enum MsgBlockSerializer implements Serializer<MsgBlock> {
    INSTANCE;

    @Override
    public MsgBlock deserialize(byte[] bytes) {
        Array array = (Array)CborSerializationUtil.deserializeOne(bytes);

        int key = ((UnsignedInteger)array.getDataItems().get(0)).getValue().intValue();
        if (key != 4)
            return null;

        ByteString dataItem = (ByteString) array.getDataItems().get(1);

        return new MsgBlock(dataItem.getBytes());
    }
}
