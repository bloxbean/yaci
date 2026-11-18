package com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.serializers.LeiosNotifySerializers;

/**
 * Server's reply to {@link MsgQuit}; ends the protocol.
 */
public class MsgDone implements Message {
    @Override
    public byte[] serialize() {
        return LeiosNotifySerializers.MsgDoneSerializer.INSTANCE.serialize(this);
    }
}
