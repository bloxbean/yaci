package com.bloxbean.cardano.yaci.core.protocol.blockfetch.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.serializers.MsgBlockSerializer;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class MsgBlock implements Message {
    private byte[] bytes;

    @Override
    public byte[] serialize() {
        return MsgBlockSerializer.INSTANCE.serialize(this);
    }
}
