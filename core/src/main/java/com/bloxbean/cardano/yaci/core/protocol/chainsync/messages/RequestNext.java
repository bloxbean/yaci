package com.bloxbean.cardano.yaci.core.protocol.chainsync.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.serializers.RequestNextSerializer;

public class RequestNext implements Message {

    @Override
    public byte[] serialize() {
        return RequestNextSerializer.INSTANCE.serialize(this);
    }
}
