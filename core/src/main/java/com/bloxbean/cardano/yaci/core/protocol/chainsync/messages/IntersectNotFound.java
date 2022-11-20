package com.bloxbean.cardano.yaci.core.protocol.chainsync.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.serializers.IntersectNotFoundSerializer;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
public class IntersectNotFound implements Message {
    private Tip tip;

    @Override
    public byte[] serialize() {
        return IntersectNotFoundSerializer.INSTANCE.serialize(this);
    }
}

