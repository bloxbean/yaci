package com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.serializers.AppMsgSubmissionSerializers;

public class MsgDone implements Message {

    @Override
    public byte[] serialize() {
        return AppMsgSubmissionSerializers.MsgDoneSerializer.INSTANCE.serialize(this);
    }
}
