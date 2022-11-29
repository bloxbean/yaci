package com.bloxbean.cardano.yaci.core.protocol.localtxmonitor.messages;

import com.bloxbean.cardano.yaci.core.protocol.localtxmonitor.serializers.LocalTxMonitorSerializers;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

@Getter
@AllArgsConstructor
@ToString
public class MsgReplyGetSizes extends MsgReply {
    private int capacityInBytes;
    private int sizeInBytes;
    private int numberOfTxs;

    @Override
    public byte[] serialize() {
        return LocalTxMonitorSerializers.MsgReplyGetSizesSerializer.INSTANCE.serialize(this);
    }
}
