package com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.messages;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessageId;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.serializers.AppMsgSubmissionSerializers;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

@Getter
public class MsgReplyMessageIds implements Message {
    private final List<AppMessageId> messageIds;

    public MsgReplyMessageIds() {
        this.messageIds = new ArrayList<>();
    }

    public MsgReplyMessageIds(List<AppMessageId> messageIds) {
        this.messageIds = messageIds != null ? messageIds : new ArrayList<>();
    }

    public void addMessageId(byte[] messageId, int size) {
        messageIds.add(new AppMessageId(messageId, size));
    }

    public void addMessageId(AppMessageId appMessageId) {
        messageIds.add(appMessageId);
    }

    @Override
    public byte[] serialize() {
        return AppMsgSubmissionSerializers.MsgReplyMessageIdsSerializer.INSTANCE.serialize(this);
    }
}
