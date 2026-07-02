package com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.serializers.LeiosNotifySerializers;

public class MsgClientDone implements Message {
    @Override
    public byte[] serialize() {
        return LeiosNotifySerializers.MsgClientDoneSerializer.INSTANCE.serialize(this);
    }
}
