package com.bloxbean.cardano.yaci.core.protocol.chainsync.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.serializers.RollbackwardSerializer;
import lombok.*;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode
@ToString
public class Rollbackward implements Message {
    private Point point;
    private Tip tip;

    @Override
    public byte[] serialize() {
        return RollbackwardSerializer.INSTANCE.serialize(this);
    }
}
