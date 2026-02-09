package com.bloxbean.cardano.yaci.core.protocol.blockfetch.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.Tag;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.messages.MsgBlock;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;

public enum MsgBlockSerializer implements Serializer<MsgBlock> {
    INSTANCE;

    private static final int KEY_VALUE = 4;

    @Override
    public MsgBlock deserialize(byte[] bytes) {
        Array array = (Array)CborSerializationUtil.deserializeOne(bytes);

        int key = ((UnsignedInteger)array.getDataItems().get(0)).getValue().intValue();
        if (key != KEY_VALUE)
            return null;

        ByteString dataItem = (ByteString) array.getDataItems().get(1);

        return new MsgBlock(dataItem.getBytes());
    }

    @Override
    public byte[] serialize(MsgBlock object) {
        Array array = new Array();
        array.add(new UnsignedInteger(KEY_VALUE));

        // Wrap block data with CBOR tag 24 (IANA tag for embedded CBOR)
        ByteString blockData = new ByteString(object.getBytes());
        blockData.setTag(new Tag(24));
        array.add(blockData);

        return CborSerializationUtil.serialize(array);
    }
}
