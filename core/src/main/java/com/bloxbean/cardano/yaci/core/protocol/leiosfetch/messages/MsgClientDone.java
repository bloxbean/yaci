package com.bloxbean.cardano.yaci.core.protocol.leiosfetch.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.serializers.LeiosFetchSerializers;

public class MsgClientDone implements Message {
    @Override
    public byte[] serialize() {
        return LeiosFetchSerializers.MsgClientDoneSerializer.INSTANCE.serialize(this);
    }
}
