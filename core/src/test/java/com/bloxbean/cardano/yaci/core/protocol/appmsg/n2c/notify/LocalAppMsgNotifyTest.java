package com.bloxbean.cardano.yaci.core.protocol.appmsg.n2c.notify;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2c.notify.messages.*;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2c.notify.serializers.LocalAppMsgNotifySerializers;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LocalAppMsgNotifyTest {

    // --- Serializer roundtrip tests ---

    @Test
    void msgRequestMessages_blocking_roundtrip() {
        MsgRequestMessages msg = new MsgRequestMessages(true);
        byte[] bytes = msg.serialize();
        MsgRequestMessages deserialized = LocalAppMsgNotifySerializers.MsgRequestMessagesSerializer.INSTANCE.deserialize(bytes);
        assertThat(deserialized.isBlocking()).isTrue();
    }

    @Test
    void msgRequestMessages_nonBlocking_roundtrip() {
        MsgRequestMessages msg = new MsgRequestMessages(false);
        byte[] bytes = msg.serialize();
        MsgRequestMessages deserialized = LocalAppMsgNotifySerializers.MsgRequestMessagesSerializer.INSTANCE.deserialize(bytes);
        assertThat(deserialized.isBlocking()).isFalse();
    }

    @Test
    void msgReplyMessagesNonBlocking_roundtrip() {
        var msg = new MsgReplyMessagesNonBlocking(List.of(testMessage()), true);
        byte[] bytes = msg.serialize();
        var deserialized = LocalAppMsgNotifySerializers.MsgReplyMessagesNonBlockingSerializer.INSTANCE.deserialize(bytes);
        assertThat(deserialized.getMessages()).hasSize(1);
        assertThat(deserialized.isHasMore()).isTrue();
        assertThat(deserialized.getMessages().get(0).getTopicId()).isEqualTo("notify-topic");
    }

    @Test
    void msgReplyMessagesBlocking_roundtrip() {
        var msg = new MsgReplyMessagesBlocking(List.of(testMessage()));
        byte[] bytes = msg.serialize();
        var deserialized = LocalAppMsgNotifySerializers.MsgReplyMessagesBlockingSerializer.INSTANCE.deserialize(bytes);
        assertThat(deserialized.getMessages()).hasSize(1);
    }

    @Test
    void msgClientDone_roundtrip() {
        MsgClientDone msg = new MsgClientDone();
        byte[] bytes = msg.serialize();
        MsgClientDone deserialized = LocalAppMsgNotifySerializers.MsgClientDoneSerializer.INSTANCE.deserialize(bytes);
        assertThat(deserialized).isNotNull();
    }

    @Test
    void stateBase_handlesAllMessageTypes() {
        LocalAppMsgNotifyStateBase stateBase = LocalAppMsgNotifyState.Idle;

        assertThat(stateBase.handleInbound(new MsgRequestMessages(true).serialize()))
                .isInstanceOf(MsgRequestMessages.class);
        assertThat(stateBase.handleInbound(new MsgReplyMessagesNonBlocking(List.of(), false).serialize()))
                .isInstanceOf(MsgReplyMessagesNonBlocking.class);
        assertThat(stateBase.handleInbound(new MsgReplyMessagesBlocking(List.of()).serialize()))
                .isInstanceOf(MsgReplyMessagesBlocking.class);
        assertThat(stateBase.handleInbound(new MsgClientDone().serialize()))
                .isInstanceOf(MsgClientDone.class);
    }

    // --- Agent state machine tests ---

    @Test
    void agent_initialState() {
        LocalAppMsgNotifyAgent agent = new LocalAppMsgNotifyAgent();
        assertThat(agent.getCurrentState()).isEqualTo(LocalAppMsgNotifyState.Idle);
        assertThat(agent.getProtocolId()).isEqualTo(102);
        assertThat(agent.hasAgency()).isTrue();
    }

    @Test
    void agent_blockingFlow() {
        LocalAppMsgNotifyAgent agent = new LocalAppMsgNotifyAgent(true);

        // Client requests (blocking)
        var request = agent.buildNextMessage();
        assertThat(request).isInstanceOf(MsgRequestMessages.class);
        assertThat(((MsgRequestMessages) request).isBlocking()).isTrue();

        agent.sendRequest(request);
        assertThat(agent.getCurrentState()).isEqualTo(LocalAppMsgNotifyState.BusyBlocking);
        assertThat(agent.hasAgency()).isFalse();

        // Server replies
        AtomicReference<List<AppMessage>> received = new AtomicReference<>();
        agent.addListener(new LocalAppMsgNotifyListener() {
            @Override
            public void onMessagesReceived(List<AppMessage> messages, boolean hasMore) {
                received.set(messages);
            }
        });

        agent.receiveResponse(new MsgReplyMessagesBlocking(List.of(testMessage())));
        assertThat(agent.getCurrentState()).isEqualTo(LocalAppMsgNotifyState.Idle);
        assertThat(received.get()).hasSize(1);
    }

    @Test
    void agent_nonBlockingFlow() {
        LocalAppMsgNotifyAgent agent = new LocalAppMsgNotifyAgent(false);

        var request = agent.buildNextMessage();
        assertThat(((MsgRequestMessages) request).isBlocking()).isFalse();

        agent.sendRequest(request);
        assertThat(agent.getCurrentState()).isEqualTo(LocalAppMsgNotifyState.BusyNonBlocking);

        agent.receiveResponse(new MsgReplyMessagesNonBlocking(List.of(), true));
        assertThat(agent.getCurrentState()).isEqualTo(LocalAppMsgNotifyState.Idle);
    }

    private AppMessage testMessage() {
        return AppMessage.builder()
                .messageId(new byte[]{7, 8, 9})
                .messageBody(new byte[]{10})
                .authMethod(0)
                .authProof(new byte[0])
                .topicId("notify-topic")
                .expiresAt(0)
                .build();
    }
}
