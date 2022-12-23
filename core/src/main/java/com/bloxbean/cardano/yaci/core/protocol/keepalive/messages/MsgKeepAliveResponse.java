package com.bloxbean.cardano.yaci.core.protocol.keepalive.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.keepalive.serializers.KeepAliveSerializers;
import lombok.*;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@ToString
public class MsgKeepAliveResponse implements Message {
    private int cookie;

    @Override
    public byte[] serialize() {
        return KeepAliveSerializers.MsgKeepAliveResponseSerializer.INSTANCE.serialize(this);
    }
}
