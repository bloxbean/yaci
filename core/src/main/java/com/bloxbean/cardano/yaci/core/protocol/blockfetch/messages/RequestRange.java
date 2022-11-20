package com.bloxbean.cardano.yaci.core.protocol.blockfetch.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.serializers.RequestRangeSerializer;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class RequestRange implements Message {
    private Point from;
    private Point to;

    @Override
    public byte[] serialize() {
        return RequestRangeSerializer.INSTANCE.serialize(this);
    }
}
