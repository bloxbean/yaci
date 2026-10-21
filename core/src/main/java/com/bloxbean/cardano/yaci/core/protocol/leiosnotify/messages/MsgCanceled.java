package com.bloxbean.cardano.yaci.core.protocol.leiosnotify.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.serializers.LeiosNotifySerializers;

/**
 * Server answer to an outstanding request after the client sent {@code MsgQuit} (Musashi rc-2026w42):
 * the request is dropped and agency returns to the client, which then gets {@code MsgDone} for its quit.
 */
public class MsgCanceled implements Message {
    @Override
    public byte[] serialize() {
        return LeiosNotifySerializers.MsgCanceledSerializer.INSTANCE.serialize(this);
    }
}
