package com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.serializers.TxSubmissionMessagesSerializers;

public class Init implements Message {

    @Override
    public byte[] serialize() {
        return TxSubmissionMessagesSerializers.InitSerializer.INSTANCE.serialize(this);
    }
}
