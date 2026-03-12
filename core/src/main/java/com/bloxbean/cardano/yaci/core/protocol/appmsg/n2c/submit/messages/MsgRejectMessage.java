package com.bloxbean.cardano.yaci.core.protocol.appmsg.n2c.submit.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2c.submit.serializers.LocalAppMsgSubmitSerializers;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
public class MsgRejectMessage implements Message {
    private RejectReason reason;
    private String detail;

    public MsgRejectMessage(RejectReason reason) {
        this(reason, "");
    }

    @Override
    public byte[] serialize() {
        return LocalAppMsgSubmitSerializers.MsgRejectMessageSerializer.INSTANCE.serialize(this);
    }
}
