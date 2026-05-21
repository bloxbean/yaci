package com.bloxbean.cardano.yaci.core.protocol.appmsg.n2c.notify.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2c.notify.serializers.LocalAppMsgNotifySerializers;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class MsgReplyMessagesBlocking implements Message {
    private final List<AppMessage> messages;

    @Override
    public byte[] serialize() {
        return LocalAppMsgNotifySerializers.MsgReplyMessagesBlockingSerializer.INSTANCE.serialize(this);
    }
}
