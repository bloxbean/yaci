package com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.messages.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AppMsgSubmissionAgentTest {

    private AppMsgSubmissionAgent agent;

    @BeforeEach
    void setUp() {
        agent = new AppMsgSubmissionAgent(100);
    }

    @Test
    void testInitialState() {
        assertThat(agent.getCurrentState()).isEqualTo(AppMsgSubmissionState.Init);
        assertThat(agent.getProtocolId()).isEqualTo(100);
        assertThat(agent.isDone()).isFalse();
        assertThat(agent.getQueueSize()).isEqualTo(0);
    }

    @Test
    void testClientHasAgencyInInit() {
        assertThat(agent.hasAgency()).isTrue();

        // buildNextMessage should return MsgInit
        var msg = agent.buildNextMessage();
        assertThat(msg).isInstanceOf(MsgInit.class);
    }

    @Test
    void testInitTransitionsToIdle() {
        // Client sends MsgInit
        agent.sendRequest(new MsgInit());
        assertThat(agent.getCurrentState()).isEqualTo(AppMsgSubmissionState.Idle);
        // In Idle, server has agency
        assertThat(agent.hasAgency()).isFalse();
    }

    @Test
    void testEnqueueMessage() {
        AppMessage msg = createTestMessage(new byte[]{1, 2, 3}, "test-topic");
        assertThat(agent.enqueueMessage(msg)).isTrue();
        assertThat(agent.getQueueSize()).isEqualTo(1);
    }

    @Test
    void testEnqueueDuplicateRejected() {
        AppMessage msg1 = createTestMessage(new byte[]{1, 2, 3}, "test");
        AppMessage msg2 = createTestMessage(new byte[]{1, 2, 3}, "test"); // same ID
        assertThat(agent.enqueueMessage(msg1)).isTrue();
        assertThat(agent.enqueueMessage(msg2)).isFalse();
        assertThat(agent.getQueueSize()).isEqualTo(1);
    }

    @Test
    void testEnqueueOverflowRejected() {
        AppMsgSubmissionAgent smallAgent = new AppMsgSubmissionAgent(2);
        assertThat(smallAgent.enqueueMessage(createTestMessage(new byte[]{1}, "t"))).isTrue();
        assertThat(smallAgent.enqueueMessage(createTestMessage(new byte[]{2}, "t"))).isTrue();
        assertThat(smallAgent.enqueueMessage(createTestMessage(new byte[]{3}, "t"))).isFalse();
        assertThat(smallAgent.getQueueSize()).isEqualTo(2);
    }

    @Test
    void testRequestMessageIdsFlow() {
        // Enqueue messages
        agent.enqueueMessage(createTestMessage(new byte[]{1}, "t"));
        agent.enqueueMessage(createTestMessage(new byte[]{2}, "t"));

        // Move to Idle
        agent.sendRequest(new MsgInit());

        // Server requests message IDs
        MsgRequestMessageIds request = new MsgRequestMessageIds(true, (short) 0, (short) 5);
        agent.receiveResponse(request);

        // Agent should be in MessageIdsBlocking, with agency
        assertThat(agent.getCurrentState()).isEqualTo(AppMsgSubmissionState.MessageIdsBlocking);
        assertThat(agent.hasAgency()).isTrue();

        // buildNextMessage should return ReplyMessageIds
        var reply = agent.buildNextMessage();
        assertThat(reply).isInstanceOf(MsgReplyMessageIds.class);
        MsgReplyMessageIds replyIds = (MsgReplyMessageIds) reply;
        assertThat(replyIds.getMessageIds()).hasSize(2);
    }

    @Test
    void testRequestMessagesFlow() {
        agent.enqueueMessage(createTestMessage(new byte[]{1}, "t"));
        agent.sendRequest(new MsgInit());

        // Server requests IDs
        agent.receiveResponse(new MsgRequestMessageIds(true, (short) 0, (short) 5));
        agent.sendRequest(agent.buildNextMessage()); // Send ReplyMessageIds

        // Server requests message bodies
        agent.receiveResponse(new MsgRequestMessages(List.of(new byte[]{1})));
        assertThat(agent.getCurrentState()).isEqualTo(AppMsgSubmissionState.Messages);

        var reply = agent.buildNextMessage();
        assertThat(reply).isInstanceOf(MsgReplyMessages.class);
        MsgReplyMessages replyMsgs = (MsgReplyMessages) reply;
        assertThat(replyMsgs.getMessages()).hasSize(1);
        assertThat(replyMsgs.getMessages().get(0).getTopicId()).isEqualTo("t");
    }

    @Test
    void testAcknowledgmentRemovesFromQueue() {
        agent.enqueueMessage(createTestMessage(new byte[]{1}, "t"));
        agent.enqueueMessage(createTestMessage(new byte[]{2}, "t"));
        agent.sendRequest(new MsgInit());

        // First request
        agent.receiveResponse(new MsgRequestMessageIds(true, (short) 0, (short) 5));
        agent.sendRequest(agent.buildNextMessage());

        // Ack 1, request more
        agent.receiveResponse(new MsgRequestMessageIds(false, (short) 1, (short) 5));
        assertThat(agent.getQueueSize()).isEqualTo(1); // One was ack'd and removed
    }

    @Test
    void testResetClearsState() {
        agent.enqueueMessage(createTestMessage(new byte[]{1}, "t"));
        agent.sendRequest(new MsgInit());

        agent.reset();

        assertThat(agent.getCurrentState()).isEqualTo(AppMsgSubmissionState.Init);
        assertThat(agent.getQueueSize()).isEqualTo(0);
    }

    private AppMessage createTestMessage(byte[] id, String topic) {
        return AppMessage.builder()
                .messageId(id)
                .messageBody(new byte[]{10, 20, 30})
                .authMethod(0)
                .authProof(new byte[0])
                .topicId(topic)
                .expiresAt(0)
                .build();
    }
}
