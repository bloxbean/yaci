package com.bloxbean.cardano.yaci.core.protocol.localtx.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.localtx.serializers.LocalTxSubmissionSerializers;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

@Getter
@AllArgsConstructor
@ToString
public class MsgRejectTx implements Message {
    private String reasonCbor;

    @Override
    public byte[] serialize() { //TODO -- Not used as client receives it
        return LocalTxSubmissionSerializers.MsgRejectTxSerializer.INSTANCE.serialize(this);
    }

}
