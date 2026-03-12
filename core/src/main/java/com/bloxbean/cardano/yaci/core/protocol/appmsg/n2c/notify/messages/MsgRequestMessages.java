package com.bloxbean.cardano.yaci.core.protocol.appmsg.n2c.notify.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2c.notify.serializers.LocalAppMsgNotifySerializers;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
public class MsgRequestMessages implements Message {
    private boolean blocking;

    @Override
    public byte[] serialize() {
        return LocalAppMsgNotifySerializers.MsgRequestMessagesSerializer.INSTANCE.serialize(this);
    }
}
