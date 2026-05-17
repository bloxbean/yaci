package com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessageId;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.messages.*;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AppMsgSubmissionServerAgentTest {

    private AppMsgSubmissionServerAgent agent;

    @BeforeEach
    void setUp() {
        agent = new AppMsgSubmissionServerAgent(AppMsgSubmissionConfig.createDefault());
    }

    @AfterEach
    void tearDown() {
        if (agent != null) agent.shutdown();
    }

    @Test
    void testInitialState() {
        assertThat(agent.getCurrentState()).isEqualTo(AppMsgSubmissionState.Init);
        assertThat(agent.getProtocolId()).isEqualTo(100);
        assertThat(agent.isDone()).isFalse();
        assertThat(agent.getReceivedMessageIdCount()).isEqualTo(0);
    }

    @Test
    void testServerAgencyInIdleState() {
        // Init state - client has agency
        assertThat(agent.hasAgency()).isFalse();

        // Process Init -> move to Idle
        agent.receiveResponse(new MsgInit());
        assertThat(agent.getCurrentState()).isEqualTo(AppMsgSubmissionState.Idle);

        // In Idle state, server has agency
        assertThat(agent.hasAgency()).isTrue();
    }

    @Test
    void testInitTriggersBlockingRequest() {
        agent.receiveResponse(new MsgInit());

        // After init, server should have a pending blocking request
        assertThat(agent.getCurrentState()).isEqualTo(AppMsgSubmissionState.Idle);
        assertThat(agent.hasAgency()).isTrue();

        // buildNextMessage should return the blocking request
        var nextMsg = agent.buildNextMessage();
        assertThat(nextMsg).isInstanceOf(MsgRequestMessageIds.class);

        MsgRequestMessageIds request = (MsgRequestMessageIds) nextMsg;
        assertThat(request.isBlocking()).isTrue();
        assertThat(request.getAckCount()).isEqualTo((short) 0);
        assertThat(request.getReqCount()).isEqualTo((short) 10);
    }

    @Test
    void testReplyMessageIdsProcessing() {
        agent.receiveResponse(new MsgInit());

        // Simulate server sending blocking request and transitioning to MessageIdsBlocking
        MsgRequestMessageIds request = new MsgRequestMessageIds(true, (short) 0, (short) 10);
        agent.sendRequest(request);
        assertThat(agent.getCurrentState()).isEqualTo(AppMsgSubmissionState.MessageIdsBlocking);

        // Track listener calls
        AtomicReference<MsgReplyMessageIds> receivedReply = new AtomicReference<>();
        agent.addListener(new AppMsgSubmissionListener() {
            @Override
            public void handleReplyMessageIds(MsgReplyMessageIds reply) {
                receivedReply.set(reply);
            }
        });

        // Client sends reply
        MsgReplyMessageIds reply = new MsgReplyMessageIds();
        reply.addMessageId(new byte[]{1, 2, 3}, 100);
        reply.addMessageId(new byte[]{4, 5, 6}, 200);
        agent.receiveResponse(reply);

        // Verify state
        assertThat(agent.getCurrentState()).isEqualTo(AppMsgSubmissionState.Idle);
        assertThat(agent.getReceivedMessageIdCount()).isEqualTo(2);
        assertThat(agent.getOutstandingMessageCount()).isEqualTo(2);
        assertThat(agent.hasReceivedMessageId(HexUtil.encodeHexString(new byte[]{1, 2, 3}))).isTrue();

        // Listener called
        assertThat(receivedReply.get()).isNotNull();
        assertThat(receivedReply.get().getMessageIds()).hasSize(2);
    }

    @Test
    void testDeduplicationOnReplyMessageIds() {
        agent.receiveResponse(new MsgInit());

        // Send blocking request -> MessageIdsBlocking
        agent.sendRequest(new MsgRequestMessageIds(true, (short) 0, (short) 10));

        // Reply with duplicate IDs
        MsgReplyMessageIds reply1 = new MsgReplyMessageIds();
        reply1.addMessageId(new byte[]{1, 2, 3}, 100);
        agent.receiveResponse(reply1);

        // Send another blocking request
        agent.sendRequest(new MsgRequestMessageIds(true, (short) 0, (short) 10));

        // Reply with same ID
        MsgReplyMessageIds reply2 = new MsgReplyMessageIds();
        reply2.addMessageId(new byte[]{1, 2, 3}, 100);
        reply2.addMessageId(new byte[]{7, 8, 9}, 300);
        agent.receiveResponse(reply2);

        // Only unique IDs should be tracked
        assertThat(agent.getReceivedMessageIdCount()).isEqualTo(2);
    }

    @Test
    void testReplyMessagesProcessing() {
        agent.receiveResponse(new MsgInit());

        // Send blocking request -> get message IDs
        agent.sendRequest(new MsgRequestMessageIds(true, (short) 0, (short) 10));

        MsgReplyMessageIds replyIds = new MsgReplyMessageIds();
        replyIds.addMessageId(new byte[]{1, 2}, 50);
        agent.receiveResponse(replyIds);
        assertThat(agent.getOutstandingMessageCount()).isEqualTo(1);

        // Server requests message bodies
        agent.sendRequest(new MsgRequestMessages(List.of(new byte[]{1, 2})));
        assertThat(agent.getCurrentState()).isEqualTo(AppMsgSubmissionState.Messages);

        // Track listener
        AtomicReference<MsgReplyMessages> receivedMsgs = new AtomicReference<>();
        agent.addListener(new AppMsgSubmissionListener() {
            @Override
            public void handleReplyMessages(MsgReplyMessages reply) {
                receivedMsgs.set(reply);
            }
        });

        // Client replies with full messages
        MsgReplyMessages replyMsgs = new MsgReplyMessages();
        replyMsgs.addMessage(AppMessage.builder()
                .messageId(new byte[]{1, 2})
                .messageBody(new byte[]{10, 20, 30})
                .authMethod(0)
                .authProof(new byte[0])
                .topicId("test")
                .expiresAt(0)
                .build());
        agent.receiveResponse(replyMsgs);

        // Verify
        assertThat(agent.getCurrentState()).isEqualTo(AppMsgSubmissionState.Idle);
        assertThat(agent.getOutstandingMessageCount()).isEqualTo(0);
        // Ack count is consumed by sendNextBlockingRequest() which fires after all messages processed
        assertThat(agent.getPendingAcknowledgments()).isEqualTo(0);
        assertThat(receivedMsgs.get()).isNotNull();
        assertThat(receivedMsgs.get().getMessages()).hasSize(1);

        // The next blocking request should carry the ack count
        var nextMsg = agent.buildNextMessage();
        assertThat(nextMsg).isInstanceOf(MsgRequestMessageIds.class);
        assertThat(((MsgRequestMessageIds) nextMsg).getAckCount()).isEqualTo((short) 1);
    }

    @Test
    void testResetClearsState() {
        agent.receiveResponse(new MsgInit());

        agent.sendRequest(new MsgRequestMessageIds(true, (short) 0, (short) 10));
        MsgReplyMessageIds reply = new MsgReplyMessageIds();
        reply.addMessageId(new byte[]{1}, 10);
        agent.receiveResponse(reply);

        agent.reset();

        assertThat(agent.getCurrentState()).isEqualTo(AppMsgSubmissionState.Init);
        assertThat(agent.getReceivedMessageIdCount()).isEqualTo(0);
        assertThat(agent.getOutstandingMessageCount()).isEqualTo(0);
        assertThat(agent.getPendingAcknowledgments()).isEqualTo(0);
    }

    @Test
    void testEmptyReplyMessageIdsSendsNextBlockingRequest() {
        agent.receiveResponse(new MsgInit());

        // Send blocking request
        agent.sendRequest(new MsgRequestMessageIds(true, (short) 0, (short) 10));

        // Reply with no messages
        agent.receiveResponse(new MsgReplyMessageIds());

        // Agent should be back in Idle with a pending blocking request
        assertThat(agent.getCurrentState()).isEqualTo(AppMsgSubmissionState.Idle);
        assertThat(agent.hasAgency()).isTrue();

        // Next message should be another blocking request
        var nextMsg = agent.buildNextMessage();
        assertThat(nextMsg).isInstanceOf(MsgRequestMessageIds.class);
        assertThat(((MsgRequestMessageIds) nextMsg).isBlocking()).isTrue();
    }
}
