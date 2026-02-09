package com.bloxbean.cardano.yaci.core.protocol.chainsync.messages;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;

public class AwaitReply implements Message {

    @Override
    public byte[] serialize() {
        Array array = new Array();
        array.add(new UnsignedInteger(1));
        return CborSerializationUtil.serialize(array, false);
    }
}
