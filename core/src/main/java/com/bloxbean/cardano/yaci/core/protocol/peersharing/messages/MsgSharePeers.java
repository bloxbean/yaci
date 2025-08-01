package com.bloxbean.cardano.yaci.core.protocol.peersharing.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.peersharing.serializers.PeerSharingSerializers;
import lombok.*;

import java.util.List;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@ToString
public class MsgSharePeers implements Message {
    private List<PeerAddress> peerAddresses;

    @Override
    public byte[] serialize() {
        return PeerSharingSerializers.MsgSharePeersSerializer.INSTANCE.serialize(this);
    }
}