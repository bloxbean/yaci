package com.bloxbean.cardano.yaci.core.protocol.blockfetch.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.serializers.BatchDoneSerializer;

public class BatchDone implements Message {
    @Override
    public byte[] serialize() {
        return BatchDoneSerializer.INSTANCE.serialize(this);
    }
}
