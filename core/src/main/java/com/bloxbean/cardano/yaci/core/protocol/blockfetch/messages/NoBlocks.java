package com.bloxbean.cardano.yaci.core.protocol.blockfetch.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.serializers.NoBlocksSerializer;

public class NoBlocks implements Message {

    @Override
    public byte[] serialize() {
        return NoBlocksSerializer.INSTANCE.serialize(this);
    }
}
