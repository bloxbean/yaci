package com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.serializers.LeiosNotifySerializers;

public class MsgLeiosNotificationRequestNext implements Message {
    @Override
    public byte[] serialize() {
        return LeiosNotifySerializers.MsgLeiosNotificationRequestNextSerializer.INSTANCE.serialize(this);
    }
}
