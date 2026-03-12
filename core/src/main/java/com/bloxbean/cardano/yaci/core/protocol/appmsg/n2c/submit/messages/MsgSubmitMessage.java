package com.bloxbean.cardano.yaci.core.protocol.appmsg.n2c.submit.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2c.submit.serializers.LocalAppMsgSubmitSerializers;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class MsgSubmitMessage implements Message {
    private final AppMessage appMessage;

    @Override
    public byte[] serialize() {
        return LocalAppMsgSubmitSerializers.MsgSubmitMessageSerializer.INSTANCE.serialize(this);
    }
}
