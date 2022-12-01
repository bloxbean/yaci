package com.bloxbean.cardano.yaci.core.protocol.localtxmonitor.messages;

import com.bloxbean.cardano.yaci.core.protocol.localtxmonitor.serializers.LocalTxMonitorSerializers;

public class MsgAwaitAcquire extends MsgQuery {
    @Override
    public byte[] serialize() {
        return LocalTxMonitorSerializers.MsgAwaitAcquireSerializer.INSTANCE.serialize(this);
    }
}
