package com.bloxbean.cardano.yaci.core.protocol.localstate.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.localstate.serializers.MsgReleaseSerializer;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@EqualsAndHashCode
@ToString
public class MsgRelease implements Message {

    @Override
    public byte[] serialize() {
        return MsgReleaseSerializer.INSTANCE.serialize(this);
    }
}
