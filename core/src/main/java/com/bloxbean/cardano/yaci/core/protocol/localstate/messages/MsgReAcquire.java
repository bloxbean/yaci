package com.bloxbean.cardano.yaci.core.protocol.localstate.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.localstate.serializers.MsgReAcquireSerializer;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

@Getter
@AllArgsConstructor
@EqualsAndHashCode
@ToString
public class MsgReAcquire implements Message {
    private Point point;

    @Override
    public byte[] serialize() {
        return MsgReAcquireSerializer.INSTANCE.serialize(this);
    }
}
