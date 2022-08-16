package com.bloxbean.cardano.yaci.core.protocol.blockfetch.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.serializers.ClientDoneSerializer;

public class ClientDone implements Message {

    @Override
    public byte[] serialize() {
        return ClientDoneSerializer.INSTANCE.serialize(this);
    }
}
