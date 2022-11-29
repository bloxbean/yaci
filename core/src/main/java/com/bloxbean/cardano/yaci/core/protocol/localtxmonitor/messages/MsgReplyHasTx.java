package com.bloxbean.cardano.yaci.core.protocol.localtxmonitor.messages;

import com.bloxbean.cardano.yaci.core.protocol.localtxmonitor.serializers.LocalTxMonitorSerializers;
import lombok.AllArgsConstructor;
import lombok.ToString;

@AllArgsConstructor
@ToString
public class MsgReplyHasTx extends MsgReply {
    private boolean hasTx;

    public boolean hasTx() {
        return hasTx;
    }

    @Override
    public byte[] serialize() {
        return LocalTxMonitorSerializers.MsgReplyHasTxSerializer.INSTANCE.serialize(this);
    }
}
