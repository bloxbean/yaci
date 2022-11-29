package com.bloxbean.cardano.yaci.core.protocol.localtxmonitor.messages;

import com.bloxbean.cardano.yaci.core.protocol.localtxmonitor.serializers.LocalTxMonitorSerializers;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class MsgReplyNextTx extends MsgReply {
    private int era;
    private byte[] transaction;

    @Override
    public byte[] serialize() {
        return LocalTxMonitorSerializers.MsgReplyNextTxSerializer.INSTANCE.serialize(this);
    }
}
