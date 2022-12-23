package com.bloxbean.cardano.yaci.core.protocol.keepalive.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.keepalive.serializers.KeepAliveSerializers;
import lombok.*;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@ToString
public class MsgKeepAlive implements Message {
    private int cookie;

    @Override
    public byte[] serialize() {
        return KeepAliveSerializers.MsgKeepAliveSerializer.INSTANCE.serialize(this);
    }
}
