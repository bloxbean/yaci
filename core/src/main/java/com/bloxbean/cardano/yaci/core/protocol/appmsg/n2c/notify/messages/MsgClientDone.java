package com.bloxbean.cardano.yaci.core.protocol.appmsg.n2c.notify.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2c.notify.serializers.LocalAppMsgNotifySerializers;

public class MsgClientDone implements Message {

    @Override
    public byte[] serialize() {
        return LocalAppMsgNotifySerializers.MsgClientDoneSerializer.INSTANCE.serialize(this);
    }
}
