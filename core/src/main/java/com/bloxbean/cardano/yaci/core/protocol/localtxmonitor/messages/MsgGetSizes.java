package com.bloxbean.cardano.yaci.core.protocol.localtxmonitor.messages;

import com.bloxbean.cardano.yaci.core.protocol.localtxmonitor.serializers.LocalTxMonitorSerializers;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

@Getter
@AllArgsConstructor
@ToString
public class MsgGetSizes extends MsgQuery {

    @Override
    public byte[] serialize() {
        return LocalTxMonitorSerializers.MsgGetSizesSerializer.INSTANCE.serialize(this);
    }
}
