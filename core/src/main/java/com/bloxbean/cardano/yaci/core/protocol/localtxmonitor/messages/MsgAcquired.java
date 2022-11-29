package com.bloxbean.cardano.yaci.core.protocol.localtxmonitor.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.localtxmonitor.serializers.LocalTxMonitorSerializers;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

@Getter
@AllArgsConstructor
@ToString
public class MsgAcquired implements Message {
    private long slotNo;

    @Override
    public byte[] serialize() {
        return LocalTxMonitorSerializers.MsgAcquiredSerializer.INSTANCE.serialize(this);
    }
}
