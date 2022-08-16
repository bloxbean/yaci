package com.bloxbean.cardano.yaci.core.protocol.handshake.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.handshake.serializers.HandshakeSerializers;
import lombok.*;

@Getter
@AllArgsConstructor
@EqualsAndHashCode
@ToString
@Builder(toBuilder = true)
public class ProposedVersions implements Message {
    private VersionTable versionTable;

    @Override
    public byte[] serialize() {
        return HandshakeSerializers.ProposedVersionSerializer.INSTANCE.serialize(this);
    }
}
