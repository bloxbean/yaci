package com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.messages.*;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.serializers.AppMsgSubmissionSerializers;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AppMsgSubmissionSerializersTest {

    @Test
    void msgInit_roundtrip() {
        MsgInit msg = new MsgInit();
        byte[] bytes = msg.serialize();

        MsgInit deserialized = AppMsgSubmissionSerializers.MsgInitSerializer.INSTANCE.deserialize(bytes);
        assertThat(deserialized).isNotNull();
    }

    @Test
    void msgRequestMessageIds_roundtrip() {
        MsgRequestMessageIds msg = new MsgRequestMessageIds(true, (short) 5, (short) 10);
        byte[] bytes = msg.serialize();

        MsgRequestMessageIds deserialized = AppMsgSubmissionSerializers.MsgRequestMessageIdsSerializer.INSTANCE.deserialize(bytes);
        assertThat(deserialized.isBlocking()).isTrue();
        assertThat(deserialized.getAckCount()).isEqualTo((short) 5);
        assertThat(deserialized.getReqCount()).isEqualTo((short) 10);
    }

    @Test
    void msgRequestMessageIds_nonBlocking_roundtrip() {
        MsgRequestMessageIds msg = new MsgRequestMessageIds(false, (short) 0, (short) 3);
        byte[] bytes = msg.serialize();

        MsgRequestMessageIds deserialized = AppMsgSubmissionSerializers.MsgRequestMessageIdsSerializer.INSTANCE.deserialize(bytes);
        assertThat(deserialized.isBlocking()).isFalse();
        assertThat(deserialized.getAckCount()).isEqualTo((short) 0);
        assertThat(deserialized.getReqCount()).isEqualTo((short) 3);
    }

    @Test
    void msgReplyMessageIds_roundtrip() {
        MsgReplyMessageIds msg = new MsgReplyMessageIds();
        msg.addMessageId(new byte[]{1, 2, 3, 4}, 100);
        msg.addMessageId(new byte[]{5, 6, 7, 8}, 200);
        byte[] bytes = msg.serialize();

        MsgReplyMessageIds deserialized = AppMsgSubmissionSerializers.MsgReplyMessageIdsSerializer.INSTANCE.deserialize(bytes);
        assertThat(deserialized.getMessageIds()).hasSize(2);
        assertThat(deserialized.getMessageIds().get(0).getMessageId()).isEqualTo(new byte[]{1, 2, 3, 4});
        assertThat(deserialized.getMessageIds().get(0).getSize()).isEqualTo(100);
        assertThat(deserialized.getMessageIds().get(1).getMessageId()).isEqualTo(new byte[]{5, 6, 7, 8});
        assertThat(deserialized.getMessageIds().get(1).getSize()).isEqualTo(200);
    }

    @Test
    void msgReplyMessageIds_empty_roundtrip() {
        MsgReplyMessageIds msg = new MsgReplyMessageIds();
        byte[] bytes = msg.serialize();

        MsgReplyMessageIds deserialized = AppMsgSubmissionSerializers.MsgReplyMessageIdsSerializer.INSTANCE.deserialize(bytes);
        assertThat(deserialized.getMessageIds()).isEmpty();
    }

    @Test
    void msgRequestMessages_roundtrip() {
        MsgRequestMessages msg = new MsgRequestMessages();
        msg.addMessageId(new byte[]{1, 2, 3});
        msg.addMessageId(new byte[]{4, 5, 6});
        byte[] bytes = msg.serialize();

        MsgRequestMessages deserialized = AppMsgSubmissionSerializers.MsgRequestMessagesSerializer.INSTANCE.deserialize(bytes);
        assertThat(deserialized.getMessageIds()).hasSize(2);
        assertThat(deserialized.getMessageIds().get(0)).isEqualTo(new byte[]{1, 2, 3});
        assertThat(deserialized.getMessageIds().get(1)).isEqualTo(new byte[]{4, 5, 6});
    }

    @Test
    void msgReplyMessages_roundtrip() {
        AppMessage appMsg = AppMessage.builder()
                .messageId(new byte[]{10, 20, 30})
                .messageBody(new byte[]{1, 2, 3, 4, 5})
                .authMethod(0)
                .authProof(new byte[]{99})
                .topicId("test-topic")
                .expiresAt(1700000000L)
                .build();

        MsgReplyMessages msg = new MsgReplyMessages();
        msg.addMessage(appMsg);
        byte[] bytes = msg.serialize();

        MsgReplyMessages deserialized = AppMsgSubmissionSerializers.MsgReplyMessagesSerializer.INSTANCE.deserialize(bytes);
        assertThat(deserialized.getMessages()).hasSize(1);

        AppMessage result = deserialized.getMessages().get(0);
        assertThat(result.getMessageId()).isEqualTo(new byte[]{10, 20, 30});
        assertThat(result.getMessageBody()).isEqualTo(new byte[]{1, 2, 3, 4, 5});
        assertThat(result.getAuthMethod()).isEqualTo(0);
        assertThat(result.getAuthProof()).isEqualTo(new byte[]{99});
        assertThat(result.getTopicId()).isEqualTo("test-topic");
        assertThat(result.getExpiresAt()).isEqualTo(1700000000L);
    }

    @Test
    void msgReplyMessages_multipleMessages_roundtrip() {
        AppMessage msg1 = AppMessage.builder()
                .messageId(new byte[]{1})
                .messageBody(new byte[]{11})
                .authMethod(0)
                .authProof(new byte[0])
                .topicId("topic-a")
                .expiresAt(0L)
                .build();

        AppMessage msg2 = AppMessage.builder()
                .messageId(new byte[]{2})
                .messageBody(new byte[]{22, 33})
                .authMethod(1)
                .authProof(new byte[]{88})
                .topicId("topic-b")
                .expiresAt(9999999999L)
                .build();

        MsgReplyMessages msg = new MsgReplyMessages(List.of(msg1, msg2));
        byte[] bytes = msg.serialize();

        MsgReplyMessages deserialized = AppMsgSubmissionSerializers.MsgReplyMessagesSerializer.INSTANCE.deserialize(bytes);
        assertThat(deserialized.getMessages()).hasSize(2);
        assertThat(deserialized.getMessages().get(0).getTopicId()).isEqualTo("topic-a");
        assertThat(deserialized.getMessages().get(1).getTopicId()).isEqualTo("topic-b");
    }

    @Test
    void msgDone_roundtrip() {
        MsgDone msg = new MsgDone();
        byte[] bytes = msg.serialize();

        MsgDone deserialized = AppMsgSubmissionSerializers.MsgDoneSerializer.INSTANCE.deserialize(bytes);
        assertThat(deserialized).isNotNull();
    }

    @Test
    void stateBase_handlesAllMessageTypes() {
        // Verify the state base can dispatch all message types
        AppMsgSubmissionStateBase stateBase = AppMsgSubmissionState.Init;

        // MsgInit (tag 0)
        byte[] initBytes = new MsgInit().serialize();
        assertThat(stateBase.handleInbound(initBytes)).isInstanceOf(MsgInit.class);

        // MsgRequestMessageIds (tag 1)
        byte[] reqIdsBytes = new MsgRequestMessageIds(true, (short) 0, (short) 5).serialize();
        assertThat(stateBase.handleInbound(reqIdsBytes)).isInstanceOf(MsgRequestMessageIds.class);

        // MsgReplyMessageIds (tag 2)
        byte[] replyIdsBytes = new MsgReplyMessageIds().serialize();
        assertThat(stateBase.handleInbound(replyIdsBytes)).isInstanceOf(MsgReplyMessageIds.class);

        // MsgRequestMessages (tag 3)
        byte[] reqMsgsBytes = new MsgRequestMessages().serialize();
        assertThat(stateBase.handleInbound(reqMsgsBytes)).isInstanceOf(MsgRequestMessages.class);

        // MsgReplyMessages (tag 4)
        byte[] replyMsgsBytes = new MsgReplyMessages().serialize();
        assertThat(stateBase.handleInbound(replyMsgsBytes)).isInstanceOf(MsgReplyMessages.class);

        // MsgDone (tag 5)
        byte[] doneBytes = new MsgDone().serialize();
        assertThat(stateBase.handleInbound(doneBytes)).isInstanceOf(MsgDone.class);
    }
}
