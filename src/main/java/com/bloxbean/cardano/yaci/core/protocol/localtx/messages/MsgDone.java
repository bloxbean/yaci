package com.bloxbean.cardano.yaci.core.protocol.localtx.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.localtx.serializers.LocalTxSubmissionSerializers;

public class MsgDone implements Message {

    @Override
    public byte[] serialize() {
        return LocalTxSubmissionSerializers.MsgDoneSerializer.INSTANCE.serialize(this);
    }
}
