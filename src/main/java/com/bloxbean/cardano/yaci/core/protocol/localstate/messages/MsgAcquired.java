package com.bloxbean.cardano.yaci.core.protocol.localstate.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.localstate.serializers.MsgAcquiredSerializer;
import lombok.ToString;

@ToString
public class MsgAcquired implements Message {

    @Override
    public byte[] serialize() {
        return MsgAcquiredSerializer.INSTANCE.serialize(this);
    }
}
