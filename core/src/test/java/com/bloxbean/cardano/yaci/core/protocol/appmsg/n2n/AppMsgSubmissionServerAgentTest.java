package com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.AppMsgTestFixtures;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.messages.*;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static com.bloxbean.cardano.yaci.core.protocol.appmsg.AppMsgTestFixtures.CHAIN;
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

    /**
     * Drive the server through MsgInit → MsgInitAck so it lands in Idle with the
     * initial blocking request pending (test mode: transitions driven manually).
     */
    private void initToIdle() {
        agent.receiveResponse(new MsgInit(List.of(CHAIN)));
        assertThat(agent.getCurrentState()).isEqualTo(AppMsgSubmissionState.InitAck);

        Message ack = agent.buildNextMessage();
        assertThat(ack).isInstanceOf(MsgInitAck.class);
        agent.sendRequest(ack); // → Idle; triggers the initial blocking request
        assertThat(agent.getCurrentState()).isEqualTo(AppMsgSubmissionState.Idle);
    }

    @Test
    void testInitialState() {
        assertThat(agent.getCurrentState()).isEqualTo(AppMsgSubmissionState.Init);
        assertThat(agent.getProtocolId()).isEqualTo(100);
        assertThat(agent.isDone()).isFalse();
        assertThat(agent.getReceivedMessageIdCount()).isEqualTo(0);
    }

    @Test
    void testInitAckCarriesServerChains() {
        agent = new AppMsgSubmissionServerAgent(AppMsgSubmissionConfig.builder()
                .chainIds(Set.of(CHAIN))
                .build());

        agent.receiveResponse(new MsgInit(List.of(CHAIN, "other")));
        assertThat(agent.getClientChainIds()).containsExactlyInAnyOrder(CHAIN, "other");
        assertThat(agent.getCurrentState()).isEqualTo(AppMsgSubmissionState.InitAck);
        assertThat(agent.hasAgency()).isTrue();

        Message ack = agent.buildNextMessage();
        assertThat(ack).isInstanceOf(MsgInitAck.class);
        assertThat(((MsgInitAck) ack).getChainIds()).containsExactly(CHAIN);
    }

    @Test
    void testInitTriggersBlockingRequestAfterInitAck() {
        initToIdle();
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
        initToIdle();

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
        assertThat(agent.getReceivedMessageIdCount()).isEqualTo(0);
        assertThat(agent.getOutstandingMessageCount()).isEqualTo(2);
        assertThat(agent.hasReceivedMessageId(HexUtil.encodeHexString(new byte[]{1, 2, 3}))).isFalse();

        // Listener called
        assertThat(receivedReply.get()).isNotNull();
        assertThat(receivedReply.get().getMessageIds()).hasSize(2);
    }

    @Test
    void testReplyMessageIdsDoesNotExceedRequestWindow() {
        initToIdle();

        agent.sendRequest(new MsgRequestMessageIds(true, (short) 0, (short) 1));

        MsgReplyMessageIds reply = new MsgReplyMessageIds();
        reply.addMessageId(new byte[]{1}, 10);
        reply.addMessageId(new byte[]{2}, 10);
        agent.receiveResponse(reply);

        assertThat(agent.getOutstandingMessageCount()).isEqualTo(1);

        Message nextMsg = agent.buildNextMessage();
        assertThat(nextMsg).isInstanceOf(MsgRequestMessages.class);
        List<byte[]> requestedIds = ((MsgRequestMessages) nextMsg).getMessageIds();
        assertThat(requestedIds).hasSize(1);
        assertThat(requestedIds.get(0)).containsExactly((byte) 1);
    }

    @Test
    void testDeduplicationAfterMessageBodyReceived() {
        initToIdle();

        AppMessage msg = AppMsgTestFixtures.message(1, "dedup-test");

        sendPendingMessageIdRequest();

        MsgReplyMessageIds reply1 = new MsgReplyMessageIds();
        reply1.addMessageId(msg.getMessageId(), msg.getSize());
        agent.receiveResponse(reply1);

        sendPendingMessageBodyRequest();
        MsgReplyMessages messages1 = new MsgReplyMessages();
        messages1.addMessage(msg);
        agent.receiveResponse(messages1);

        assertThat(agent.getReceivedMessageIdCount()).isEqualTo(1);
        assertThat(agent.hasReceivedMessageId(msg.getMessageIdHex())).isTrue();

        sendPendingMessageIdRequest();

        AppMessage msg2 = AppMsgTestFixtures.message(2, "dedup-test-2");
        MsgReplyMessageIds reply2 = new MsgReplyMessageIds();
        reply2.addMessageId(msg.getMessageId(), msg.getSize());
        reply2.addMessageId(msg2.getMessageId(), msg2.getSize());
        agent.receiveResponse(reply2);

        assertThat(agent.getReceivedMessageIdCount()).isEqualTo(1);
        assertThat(agent.getOutstandingMessageCount()).isEqualTo(1);
    }

    @Test
    void testReplyMessagesProcessing() {
        initToIdle();

        AppMessage msg = AppMsgTestFixtures.message(CHAIN, "test", 1, new byte[]{10, 20, 30});

        // Send blocking request -> get message IDs
        agent.sendRequest(new MsgRequestMessageIds(true, (short) 0, (short) 10));

        MsgReplyMessageIds replyIds = new MsgReplyMessageIds();
        replyIds.addMessageId(msg.getMessageId(), msg.getSize());
        agent.receiveResponse(replyIds);
        assertThat(agent.getOutstandingMessageCount()).isEqualTo(1);

        // Server requests message bodies
        agent.sendRequest(new MsgRequestMessages(List.of(msg.getMessageId())));
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
        replyMsgs.addMessage(msg);
        agent.receiveResponse(replyMsgs);

        // Verify
        assertThat(agent.getCurrentState()).isEqualTo(AppMsgSubmissionState.Idle);
        assertThat(agent.getOutstandingMessageCount()).isEqualTo(0);
        // Ack count is consumed by sendNextBlockingRequest() which fires after all messages processed
        assertThat(agent.getPendingAcknowledgments()).isEqualTo(0);
        assertThat(receivedMsgs.get()).isNotNull();
        assertThat(receivedMsgs.get().getMessages()).hasSize(1);
        assertThat(agent.hasReceivedMessageId(msg.getMessageIdHex())).isTrue();

        // The next blocking request should carry the ack count
        var nextMsg = agent.buildNextMessage();
        assertThat(nextMsg).isInstanceOf(MsgRequestMessageIds.class);
        assertThat(((MsgRequestMessageIds) nextMsg).getAckCount()).isEqualTo((short) 1);
    }

    @Test
    void testInvalidMessageIdRejectedButAcked() {
        initToIdle();

        AppMessage valid = AppMsgTestFixtures.message(1, "valid");
        // Tampered: claims valid's id but different body → id recompute fails
        AppMessage forged = AppMessage.builder()
                .messageId(valid.getMessageId())
                .chainId(valid.getChainId())
                .topic(valid.getTopic())
                .sender(valid.getSender())
                .senderSeq(valid.getSenderSeq())
                .expiresAt(valid.getExpiresAt())
                .body(new byte[]{99, 98, 97})
                .authScheme(valid.getAuthScheme())
                .authProof(valid.getAuthProof())
                .build();

        deliverMessage(forged);

        assertThat(agent.getRejectedMessageCount()).isEqualTo(1);
        // Rejected but acked: id is in processed cache, window advanced via next request
        assertThat(agent.hasReceivedMessageId(valid.getMessageIdHex())).isTrue();
        Message nextMsg = agent.buildNextMessage();
        assertThat(((MsgRequestMessageIds) nextMsg).getAckCount()).isEqualTo((short) 1);
    }

    @Test
    void testExpiredMessageRejected() {
        initToIdle();

        long past = System.currentTimeMillis() / 1000 - 10;
        AppMessage expired = AppMsgTestFixtures.message(CHAIN, "", 1, new byte[]{1}, past);

        AtomicReference<MsgReplyMessages> delivered = new AtomicReference<>();
        agent.addListener(new AppMsgSubmissionListener() {
            @Override
            public void handleReplyMessages(MsgReplyMessages reply) {
                delivered.set(reply);
            }
        });

        deliverMessage(expired);

        assertThat(agent.getRejectedMessageCount()).isEqualTo(1);
        assertThat(delivered.get().getMessages()).isEmpty();
    }

    @Test
    void testTtlTooFarInFutureRejected() {
        agent = new AppMsgSubmissionServerAgent(AppMsgSubmissionConfig.builder()
                .maxTtlSeconds(60)
                .build());
        initToIdle();

        long tooFar = System.currentTimeMillis() / 1000 + 3600;
        AppMessage msg = AppMsgTestFixtures.message(CHAIN, "", 1, new byte[]{1}, tooFar);

        deliverMessage(msg);

        assertThat(agent.getRejectedMessageCount()).isEqualTo(1);
    }

    @Test
    void testOversizedMessageRejected() {
        agent = new AppMsgSubmissionServerAgent(AppMsgSubmissionConfig.builder()
                .maxMessageSize(2)
                .build());
        initToIdle();

        AppMessage big = AppMsgTestFixtures.message(CHAIN, "", 1, new byte[]{1, 2, 3});

        deliverMessage(big);

        assertThat(agent.getRejectedMessageCount()).isEqualTo(1);
    }

    @Test
    void testUnservedChainRejected() {
        agent = new AppMsgSubmissionServerAgent(AppMsgSubmissionConfig.builder()
                .chainIds(Set.of("served-chain"))
                .build());
        initToIdle();

        AppMessage otherChain = AppMsgTestFixtures.message("other-chain", "", 1, new byte[]{1});

        deliverMessage(otherChain);

        assertThat(agent.getRejectedMessageCount()).isEqualTo(1);
    }

    @Test
    void testCustomValidatorRejection() {
        agent = new AppMsgSubmissionServerAgent(AppMsgSubmissionConfig.builder()
                .validator(m -> AppMsgValidator.Result.reject("not on allow-list"))
                .build());
        initToIdle();

        AppMessage msg = AppMsgTestFixtures.message(1, "anything");

        AtomicReference<MsgReplyMessages> delivered = new AtomicReference<>();
        agent.addListener(new AppMsgSubmissionListener() {
            @Override
            public void handleReplyMessages(MsgReplyMessages reply) {
                delivered.set(reply);
            }
        });

        deliverMessage(msg);

        assertThat(agent.getRejectedMessageCount()).isEqualTo(1);
        assertThat(delivered.get().getMessages()).isEmpty();
    }

    @Test
    void testResetClearsState() {
        initToIdle();

        agent.sendRequest(new MsgRequestMessageIds(true, (short) 0, (short) 10));
        MsgReplyMessageIds reply = new MsgReplyMessageIds();
        reply.addMessageId(new byte[]{1}, 10);
        agent.receiveResponse(reply);

        agent.reset();

        assertThat(agent.getCurrentState()).isEqualTo(AppMsgSubmissionState.Init);
        assertThat(agent.getReceivedMessageIdCount()).isEqualTo(0);
        assertThat(agent.getOutstandingMessageCount()).isEqualTo(0);
        assertThat(agent.getPendingAcknowledgments()).isEqualTo(0);
        assertThat(agent.getRejectedMessageCount()).isEqualTo(0);
    }

    @Test
    void testEmptyReplyMessageIdsSendsNextBlockingRequest() {
        initToIdle();

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

    @Test
    void testOutOfOrderReplyMessagesAreNotAcknowledged() {
        initToIdle();
        sendPendingMessageIdRequest();

        AppMessage msg1 = AppMsgTestFixtures.message(1, "ooo-1");
        AppMessage msg2 = AppMsgTestFixtures.message(2, "ooo-2");

        MsgReplyMessageIds replyIds = new MsgReplyMessageIds();
        replyIds.addMessageId(msg1.getMessageId(), msg1.getSize());
        replyIds.addMessageId(msg2.getMessageId(), msg2.getSize());
        agent.receiveResponse(replyIds);
        assertThat(agent.getOutstandingMessageCount()).isEqualTo(2);

        MsgRequestMessages bodyRequest = sendPendingMessageBodyRequest();
        assertThat(bodyRequest.getMessageIds()).hasSize(2);

        MsgReplyMessages partialReply = new MsgReplyMessages();
        partialReply.addMessage(msg2);
        agent.receiveResponse(partialReply);

        assertThat(agent.getCurrentState()).isEqualTo(AppMsgSubmissionState.Idle);
        assertThat(agent.getOutstandingMessageCount()).isEqualTo(0);
        assertThat(agent.hasReceivedMessageId(msg1.getMessageIdHex())).isFalse();
        assertThat(agent.hasReceivedMessageId(msg2.getMessageIdHex())).isFalse();

        Message nextMsg = agent.buildNextMessage();
        assertThat(nextMsg).isInstanceOf(MsgRequestMessageIds.class);
        assertThat(((MsgRequestMessageIds) nextMsg).getAckCount()).isEqualTo((short) 0);
    }

    @Test
    void testPrefixPartialReplyMessagesAckOnlyAcceptedPrefix() {
        initToIdle();
        sendPendingMessageIdRequest();

        AppMessage msg1 = AppMsgTestFixtures.message(1, "prefix-1");
        AppMessage msg2 = AppMsgTestFixtures.message(2, "prefix-2");

        MsgReplyMessageIds replyIds = new MsgReplyMessageIds();
        replyIds.addMessageId(msg1.getMessageId(), msg1.getSize());
        replyIds.addMessageId(msg2.getMessageId(), msg2.getSize());
        agent.receiveResponse(replyIds);

        sendPendingMessageBodyRequest();

        MsgReplyMessages partialReply = new MsgReplyMessages();
        partialReply.addMessage(msg1);
        agent.receiveResponse(partialReply);

        assertThat(agent.getOutstandingMessageCount()).isEqualTo(0);
        assertThat(agent.hasReceivedMessageId(msg1.getMessageIdHex())).isTrue();
        assertThat(agent.hasReceivedMessageId(msg2.getMessageIdHex())).isFalse();

        Message nextMsg = agent.buildNextMessage();
        assertThat(nextMsg).isInstanceOf(MsgRequestMessageIds.class);
        assertThat(((MsgRequestMessageIds) nextMsg).getAckCount()).isEqualTo((short) 1);
    }

    @Test
    void testProcessedMessageIdCacheIsBounded() {
        agent = new AppMsgSubmissionServerAgent(AppMsgSubmissionConfig.builder()
                .processedMessageIdCacheSize(2)
                .build());

        initToIdle();
        AppMessage m1 = AppMsgTestFixtures.message(1, "bounded-1");
        AppMessage m2 = AppMsgTestFixtures.message(2, "bounded-2");
        AppMessage m3 = AppMsgTestFixtures.message(3, "bounded-3");
        completeMessage(m1);
        completeMessage(m2);
        completeMessage(m3);

        assertThat(agent.getReceivedMessageIdCount()).isEqualTo(2);
        assertThat(agent.hasReceivedMessageId(m1.getMessageIdHex())).isFalse();
        assertThat(agent.hasReceivedMessageId(m2.getMessageIdHex())).isTrue();
        assertThat(agent.hasReceivedMessageId(m3.getMessageIdHex())).isTrue();
    }

    /** Announce + fetch + deliver a single message body through the full pull cycle. */
    private void deliverMessage(AppMessage msg) {
        sendPendingMessageIdRequest();

        MsgReplyMessageIds replyIds = new MsgReplyMessageIds();
        replyIds.addMessageId(msg.getMessageId(), msg.getSize());
        agent.receiveResponse(replyIds);

        sendPendingMessageBodyRequest();

        MsgReplyMessages replyMessages = new MsgReplyMessages();
        replyMessages.addMessage(msg);
        agent.receiveResponse(replyMessages);
    }

    private MsgRequestMessageIds sendPendingMessageIdRequest() {
        Message nextMsg = agent.buildNextMessage();
        assertThat(nextMsg).isInstanceOf(MsgRequestMessageIds.class);
        agent.sendRequest(nextMsg);
        return (MsgRequestMessageIds) nextMsg;
    }

    private MsgRequestMessages sendPendingMessageBodyRequest() {
        Message nextMsg = agent.buildNextMessage();
        assertThat(nextMsg).isInstanceOf(MsgRequestMessages.class);
        agent.sendRequest(nextMsg);
        return (MsgRequestMessages) nextMsg;
    }

    private void completeMessage(AppMessage msg) {
        deliverMessage(msg);
    }
}
