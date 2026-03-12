package com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.serializers.AppMsgSubmissionSerializers;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

@Getter
public class MsgRequestMessages implements Message {
    private final List<byte[]> messageIds;

    public MsgRequestMessages() {
        this.messageIds = new ArrayList<>();
    }

    public MsgRequestMessages(List<byte[]> messageIds) {
        this.messageIds = messageIds != null ? messageIds : new ArrayList<>();
    }

    public void addMessageId(byte[] messageId) {
        messageIds.add(messageId);
    }

    @Override
    public byte[] serialize() {
        return AppMsgSubmissionSerializers.MsgRequestMessagesSerializer.INSTANCE.serialize(this);
    }
}
