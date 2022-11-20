package com.bloxbean.cardano.yaci.core.protocol.blockfetch.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.serializers.StartBatchSerializer;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class StartBatch implements Message { //NOT USED


    @Override
    public byte[] serialize() {
        return StartBatchSerializer.INSTANCE.serialize(this);
    }
}
