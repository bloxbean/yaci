package com.bloxbean.cardano.yaci.core.protocol.peersharing.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.peersharing.serializers.PeerSharingSerializers;
import lombok.*;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@ToString
public class MsgShareRequest implements Message {
    public static final int MAX_PEERS_REQUEST = 100;
    
    private int amount;

    @Override
    public byte[] serialize() {
        return PeerSharingSerializers.MsgShareRequestSerializer.INSTANCE.serialize(this);
    }
}