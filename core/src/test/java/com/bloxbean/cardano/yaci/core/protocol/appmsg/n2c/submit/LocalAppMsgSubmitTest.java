package com.bloxbean.cardano.yaci.core.protocol.appmsg.n2c.submit;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2c.submit.messages.*;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2c.submit.serializers.LocalAppMsgSubmitSerializers;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LocalAppMsgSubmitTest {

    // --- Serializer roundtrip tests ---

    @Test
    void msgSubmitMessage_roundtrip() {
        AppMessage appMsg = testMessage();
        MsgSubmitMessage msg = new MsgSubmitMessage(appMsg);
        byte[] bytes = msg.serialize();

        MsgSubmitMessage deserialized = LocalAppMsgSubmitSerializers.MsgSubmitMessageSerializer.INSTANCE.deserialize(bytes);
        assertThat(deserialized.getAppMessage().getMessageId()).isEqualTo(new byte[]{1, 2, 3});
        assertThat(deserialized.getAppMessage().getTopicId()).isEqualTo("test");
    }

    @Test
    void msgAcceptMessage_roundtrip() {
        MsgAcceptMessage msg = new MsgAcceptMessage();
        byte[] bytes = msg.serialize();
        MsgAcceptMessage deserialized = LocalAppMsgSubmitSerializers.MsgAcceptMessageSerializer.INSTANCE.deserialize(bytes);
        assertThat(deserialized).isNotNull();
    }

    @Test
    void msgRejectMessage_roundtrip() {
        MsgRejectMessage msg = new MsgRejectMessage(RejectReason.EXPIRED, "TTL exceeded");
        byte[] bytes = msg.serialize();
        MsgRejectMessage deserialized = LocalAppMsgSubmitSerializers.MsgRejectMessageSerializer.INSTANCE.deserialize(bytes);
        assertThat(deserialized.getReason()).isEqualTo(RejectReason.EXPIRED);
        assertThat(deserialized.getDetail()).isEqualTo("TTL exceeded");
    }

    @Test
    void msgDone_roundtrip() {
        MsgDone msg = new MsgDone();
        byte[] bytes = msg.serialize();
        MsgDone deserialized = LocalAppMsgSubmitSerializers.MsgDoneSerializer.INSTANCE.deserialize(bytes);
        assertThat(deserialized).isNotNull();
    }

    @Test
    void stateBase_handlesAllMessageTypes() {
        LocalAppMsgSubmitStateBase stateBase = LocalAppMsgSubmitState.Idle;

        assertThat(stateBase.handleInbound(new MsgSubmitMessage(testMessage()).serialize()))
                .isInstanceOf(MsgSubmitMessage.class);
        assertThat(stateBase.handleInbound(new MsgAcceptMessage().serialize()))
                .isInstanceOf(MsgAcceptMessage.class);
        assertThat(stateBase.handleInbound(new MsgRejectMessage(RejectReason.INVALID, "bad").serialize()))
                .isInstanceOf(MsgRejectMessage.class);
        assertThat(stateBase.handleInbound(new MsgDone().serialize()))
                .isInstanceOf(MsgDone.class);
    }

    // --- Agent state machine tests ---

    @Test
    void agent_initialState() {
        LocalAppMsgSubmitAgent agent = new LocalAppMsgSubmitAgent();
        assertThat(agent.getCurrentState()).isEqualTo(LocalAppMsgSubmitState.Idle);
        assertThat(agent.getProtocolId()).isEqualTo(101);
        assertThat(agent.hasAgency()).isTrue(); // Client has agency in Idle
    }

    @Test
    void agent_submitFlow() {
        LocalAppMsgSubmitAgent agent = new LocalAppMsgSubmitAgent();
        agent.submitMessage(testMessage());

        // Build submit message
        var msg = agent.buildNextMessage();
        assertThat(msg).isInstanceOf(MsgSubmitMessage.class);

        // Transition to Busy
        agent.sendRequest(msg);
        assertThat(agent.getCurrentState()).isEqualTo(LocalAppMsgSubmitState.Busy);
        assertThat(agent.hasAgency()).isFalse();

        // Server accepts
        agent.receiveResponse(new MsgAcceptMessage());
        assertThat(agent.getCurrentState()).isEqualTo(LocalAppMsgSubmitState.Idle);
    }

    @Test
    void agent_rejectFlow() {
        LocalAppMsgSubmitAgent agent = new LocalAppMsgSubmitAgent();
        agent.submitMessage(testMessage());

        agent.sendRequest(agent.buildNextMessage());
        agent.receiveResponse(new MsgRejectMessage(RejectReason.ALREADY_RECEIVED));
        assertThat(agent.getCurrentState()).isEqualTo(LocalAppMsgSubmitState.Idle);
    }

    private AppMessage testMessage() {
        return AppMessage.builder()
                .messageId(new byte[]{1, 2, 3})
                .messageBody(new byte[]{10, 20})
                .authMethod(0)
                .authProof(new byte[0])
                .topicId("test")
                .expiresAt(0)
                .build();
    }
}
