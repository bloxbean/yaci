package com.bloxbean.cardano.yaci.core.protocol.peersharing.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.peersharing.serializers.PeerSharingSerializers;
import lombok.*;

@Getter
@NoArgsConstructor
@Builder
@ToString
public class MsgDone implements Message {
    
    @Override
    public byte[] serialize() {
        return PeerSharingSerializers.MsgDoneSerializer.INSTANCE.serialize(this);
    }
}