package com.bloxbean.cardano.yaci.core.protocol.localstate.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.localstate.serializers.MsgResultSerializer;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

@Getter
@AllArgsConstructor
@ToString
public class MsgResult implements Message {
    private byte[] result;

    @Override
    public byte[] serialize() {
        return MsgResultSerializer.INSTANCE.serialize(this);
    }
}
