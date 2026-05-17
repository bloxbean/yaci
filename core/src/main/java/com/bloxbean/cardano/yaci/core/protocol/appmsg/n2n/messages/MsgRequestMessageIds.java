package com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.serializers.AppMsgSubmissionSerializers;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class MsgRequestMessageIds implements Message {
    private boolean blocking;
    private short ackCount;
    private short reqCount;

    @Override
    public byte[] serialize() {
        return AppMsgSubmissionSerializers.MsgRequestMessageIdsSerializer.INSTANCE.serialize(this);
    }
}
