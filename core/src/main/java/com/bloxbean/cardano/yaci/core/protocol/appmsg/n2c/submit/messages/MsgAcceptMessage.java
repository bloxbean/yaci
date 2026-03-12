package com.bloxbean.cardano.yaci.core.protocol.appmsg.n2c.submit.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2c.submit.serializers.LocalAppMsgSubmitSerializers;

public class MsgAcceptMessage implements Message {

    @Override
    public byte[] serialize() {
        return LocalAppMsgSubmitSerializers.MsgAcceptMessageSerializer.INSTANCE.serialize(this);
    }
}
