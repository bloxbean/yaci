package com.bloxbean.cardano.yaci.core.protocol.localstate.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.localstate.serializers.MsgFailureSerializer;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

@Getter
@AllArgsConstructor
@ToString
public class MsgFailure implements Message {
    public enum Reason {
        ACQUIRE_FAILURE_POINT_TOO_OLD, ACQUIRE_FAILURE_POINT_NOT_ON_CHAIN;
    }

    private Reason reason;

    @Override
    public byte[] serialize() {
        return MsgFailureSerializer.INSTANCE.serialize(this);
    }
}
