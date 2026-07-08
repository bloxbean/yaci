package com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.AppMsgTestFixtures;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AuthScheme;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.messages.*;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.serializers.AppMsgSubmissionSerializers;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AppMsgSubmissionSerializersTest {

    @Test
    void msgInit_roundtrip() {
        MsgInit msg = new MsgInit(List.of("chain-a", "chain-b"));
        byte[] bytes = msg.serialize();

        MsgInit deserialized = AppMsgSubmissionSerializers.MsgInitSerializer.INSTANCE.deserialize(bytes);
        assertThat(deserialized).isNotNull();
        assertThat(deserialized.getChainIds()).containsExactly("chain-a", "chain-b");
    }

    @Test
    void msgInit_empty_roundtrip() {
        MsgInit msg = new MsgInit();
        byte[] bytes = msg.serialize();

        MsgInit deserialized = AppMsgSubmissionSerializers.MsgInitSerializer.INSTANCE.deserialize(bytes);
        assertThat(deserialized.getChainIds()).isEmpty();
    }

    @Test
    void msgInitAck_roundtrip() {
        MsgInitAck msg = new MsgInitAck(List.of("chain-a"));
        byte[] bytes = msg.serialize();

        MsgInitAck deserialized = AppMsgSubmissionSerializers.MsgInitAckSerializer.INSTANCE.deserialize(bytes);
        assertThat(deserialized).isNotNull();
        assertThat(deserialized.getChainIds()).containsExactly("chain-a");
    }

    @Test
    void msgRequestMessageIds_roundtrip() {
        MsgRequestMessageIds msg = new MsgRequestMessageIds(true, (short) 5, (short) 10);
        byte[] bytes = msg.serialize();

        MsgRequestMessageIds deserialized =
                AppMsgSubmissionSerializers.MsgRequestMessageIdsSerializer.INSTANCE.deserialize(bytes);
        assertThat(deserialized.isBlocking()).isTrue();
        assertThat(deserialized.getAckCount()).isEqualTo((short) 5);
        assertThat(deserialized.getReqCount()).isEqualTo((short) 10);
    }

    @Test
    void msgRequestMessageIds_nonBlocking_roundtrip() {
        MsgRequestMessageIds msg = new MsgRequestMessageIds(false, (short) 0, (short) 3);
        byte[] bytes = msg.serialize();

        MsgRequestMessageIds deserialized =
                AppMsgSubmissionSerializers.MsgRequestMessageIdsSerializer.INSTANCE.deserialize(bytes);
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

        MsgReplyMessageIds deserialized =
                AppMsgSubmissionSerializers.MsgReplyMessageIdsSerializer.INSTANCE.deserialize(bytes);
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

        MsgReplyMessageIds deserialized =
                AppMsgSubmissionSerializers.MsgReplyMessageIdsSerializer.INSTANCE.deserialize(bytes);
        assertThat(deserialized.getMessageIds()).isEmpty();
    }

    @Test
    void msgRequestMessages_roundtrip() {
        MsgRequestMessages msg = new MsgRequestMessages();
        msg.addMessageId(new byte[]{1, 2, 3});
        msg.addMessageId(new byte[]{4, 5, 6});
        byte[] bytes = msg.serialize();

        MsgRequestMessages deserialized =
                AppMsgSubmissionSerializers.MsgRequestMessagesSerializer.INSTANCE.deserialize(bytes);
        assertThat(deserialized.getMessageIds()).hasSize(2);
        assertThat(deserialized.getMessageIds().get(0)).isEqualTo(new byte[]{1, 2, 3});
        assertThat(deserialized.getMessageIds().get(1)).isEqualTo(new byte[]{4, 5, 6});
    }

    @Test
    void msgReplyMessages_roundtrip() {
        AppMessage appMsg = AppMsgTestFixtures.message("audit-chain", "orders", 7,
                new byte[]{1, 2, 3, 4, 5}, 1900000000L);

        MsgReplyMessages msg = new MsgReplyMessages();
        msg.addMessage(appMsg);
        byte[] bytes = msg.serialize();

        MsgReplyMessages deserialized =
                AppMsgSubmissionSerializers.MsgReplyMessagesSerializer.INSTANCE.deserialize(bytes);
        assertThat(deserialized.getMessages()).hasSize(1);

        AppMessage result = deserialized.getMessages().get(0);
        assertThat(result.getVersion()).isEqualTo(AppMessage.ENVELOPE_VERSION);
        assertThat(result.getMessageId()).isEqualTo(appMsg.getMessageId());
        assertThat(result.getChainId()).isEqualTo("audit-chain");
        assertThat(result.getTopic()).isEqualTo("orders");
        assertThat(result.getSender()).isEqualTo(AppMsgTestFixtures.SENDER);
        assertThat(result.getSenderSeq()).isEqualTo(7);
        assertThat(result.getExpiresAt()).isEqualTo(1900000000L);
        assertThat(result.getBody()).isEqualTo(new byte[]{1, 2, 3, 4, 5});
        assertThat(result.getAuthScheme()).isEqualTo(AuthScheme.ED25519.getValue());
        assertThat(result.hasValidMessageId()).isTrue();
    }

    @Test
    void msgReplyMessages_multipleMessages_roundtrip() {
        AppMessage msg1 = AppMsgTestFixtures.message("chain-1", "topic-a", 1,
                new byte[]{11}, 1900000000L);
        AppMessage msg2 = AppMsgTestFixtures.message("chain-2", "topic-b", 2,
                new byte[]{22, 33}, 1900000001L);

        MsgReplyMessages msg = new MsgReplyMessages(List.of(msg1, msg2));
        byte[] bytes = msg.serialize();

        MsgReplyMessages deserialized =
                AppMsgSubmissionSerializers.MsgReplyMessagesSerializer.INSTANCE.deserialize(bytes);
        assertThat(deserialized.getMessages()).hasSize(2);
        assertThat(deserialized.getMessages().get(0).getTopic()).isEqualTo("topic-a");
        assertThat(deserialized.getMessages().get(0).getChainId()).isEqualTo("chain-1");
        assertThat(deserialized.getMessages().get(1).getTopic()).isEqualTo("topic-b");
        assertThat(deserialized.getMessages().get(1).getChainId()).isEqualTo("chain-2");
    }

    @Test
    void messageId_isContentDerived_andTamperEvident() {
        AppMessage msg = AppMsgTestFixtures.message("chain-x", "t", 42,
                "hello".getBytes(StandardCharsets.UTF_8), 1900000000L);
        assertThat(msg.hasValidMessageId()).isTrue();

        // Same content → same id (deterministic)
        byte[] again = AppMessage.computeMessageId("chain-x", "t", AppMsgTestFixtures.SENDER,
                42, 1900000000L, "hello".getBytes(StandardCharsets.UTF_8));
        assertThat(again).isEqualTo(msg.getMessageId());

        // Tampered body → id check fails
        AppMessage tampered = AppMessage.builder()
                .messageId(msg.getMessageId())
                .chainId(msg.getChainId())
                .topic(msg.getTopic())
                .sender(msg.getSender())
                .senderSeq(msg.getSenderSeq())
                .expiresAt(msg.getExpiresAt())
                .body("HELLO".getBytes(StandardCharsets.UTF_8))
                .authScheme(msg.getAuthScheme())
                .authProof(msg.getAuthProof())
                .build();
        assertThat(tampered.hasValidMessageId()).isFalse();
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
        byte[] initBytes = new MsgInit(List.of("c1")).serialize();
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

        // MsgInitAck (tag 6)
        byte[] initAckBytes = new MsgInitAck(List.of("c1")).serialize();
        assertThat(stateBase.handleInbound(initAckBytes)).isInstanceOf(MsgInitAck.class);
    }
}
