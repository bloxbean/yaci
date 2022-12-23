package com.bloxbean.cardano.yaci.core.protocol.keepalive.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.keepalive.serializers.KeepAliveSerializers;

public class MsgDone implements Message {

    @Override
    public byte[] serialize() {
        return KeepAliveSerializers.MsgDoneSerializer.INSTANCE.serialize(this);
    }
}
