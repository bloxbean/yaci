package com.bloxbean.cardano.yaci.core.protocol.localstate.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.localstate.serializers.MsgDoneSerializer;
import lombok.ToString;

@ToString
public class MsgDone implements Message {

    @Override
    public byte[] serialize() {
        return MsgDoneSerializer.INSTANCE.serialize(this);
    }
}
