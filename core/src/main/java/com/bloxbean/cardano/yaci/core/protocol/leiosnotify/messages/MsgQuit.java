package com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.serializers.LeiosNotifySerializers;

/**
 * Client asks to end the protocol; the server answers with {@link MsgDone}.
 */
public class MsgQuit implements Message {
    @Override
    public byte[] serialize() {
        return LeiosNotifySerializers.MsgQuitSerializer.INSTANCE.serialize(this);
    }
}
