package com.bloxbean.cardano.yaci.core.protocol.chainsync.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.serializers.IntersectFoundSerializer;
import lombok.*;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
public class IntersectFound implements Message {
    private Point point;
    private Tip tip;

    @Override
    public byte[] serialize() {
        return IntersectFoundSerializer.INSTANCE.serialize(this);
    }
}

