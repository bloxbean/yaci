package com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.serializers.AppMsgSubmissionSerializers;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

@Getter
public class MsgReplyMessages implements Message {
    private final List<AppMessage> messages;

    public MsgReplyMessages() {
        this.messages = new ArrayList<>();
    }

    public MsgReplyMessages(List<AppMessage> messages) {
        this.messages = messages != null ? messages : new ArrayList<>();
    }

    public void addMessage(AppMessage appMessage) {
        messages.add(appMessage);
    }

    @Override
    public byte[] serialize() {
        return AppMsgSubmissionSerializers.MsgReplyMessagesSerializer.INSTANCE.serialize(this);
    }
}
