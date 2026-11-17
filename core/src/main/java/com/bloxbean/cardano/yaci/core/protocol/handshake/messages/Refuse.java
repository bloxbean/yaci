package com.bloxbean.cardano.yaci.core.protocol.handshake.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.handshake.serializers.HandshakeSerializers;
import lombok.*;

@Getter
@AllArgsConstructor
@EqualsAndHashCode
@ToString
@Builder(toBuilder = true)
public class Refuse implements Message {
    private Reason reason;

    @Override
    public byte[] serialize() {
        return HandshakeSerializers.RefuseSerializer.INSTANCE.serialize(this);
    }
}
