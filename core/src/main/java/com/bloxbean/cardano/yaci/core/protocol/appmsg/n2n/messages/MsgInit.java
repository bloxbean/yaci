package com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.serializers.AppMsgSubmissionSerializers;

public class MsgInit implements Message {

    @Override
    public byte[] serialize() {
        return AppMsgSubmissionSerializers.MsgInitSerializer.INSTANCE.serialize(this);
    }
}
