package com.bloxbean.cardano.yaci.core.protocol.localstate.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.Query;
import com.bloxbean.cardano.yaci.core.protocol.localstate.serializers.MsgQuerySerializer;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

@Getter
@AllArgsConstructor
@ToString
public class MsgQuery implements Message {
    private Query query;

    @Override
    public byte[] serialize() {
        return MsgQuerySerializer.INSTANCE.serialize(this);
    }
}
