package com.bloxbean.cardano.yaci.core.protocol.localtx.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.localtx.serializers.LocalTxSubmissionSerializers;

public class MsgAcceptTx implements Message {

    @Override
    public byte[] serialize() {
        return LocalTxSubmissionSerializers.MsgAcceptTxSerializer.INSTANCE.serialize(this);
    }
}
