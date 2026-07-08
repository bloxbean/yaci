package com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.AppMsgTestFixtures;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.messages.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.bloxbean.cardano.yaci.core.protocol.appmsg.AppMsgTestFixtures.CHAIN;
import static org.assertj.core.api.Assertions.assertThat;

class AppMsgSubmissionAgentTest {

    private AppMsgSubmissionAgent agent;

    @BeforeEach
    void setUp() {
        agent = new AppMsgSubmissionAgent(configFor(CHAIN), 100);
    }

    private static AppMsgSubmissionConfig configFor(String... chains) {
        return AppMsgSubmissionConfig.builder()
                .chainIds(Set.of(chains))
                .build();
    }

    /** Drive the client through MsgInit → MsgInitAck so it lands in Idle with negotiated chains. */
    private void completeInit(String... serverChains) {
        agent.sendRequest(new MsgInit(List.of(CHAIN)));
        agent.receiveResponse(new MsgInitAck(List.of(serverChains)));
    }

    @Test
    void testInitialState() {
        assertThat(agent.getCurrentState()).isEqualTo(AppMsgSubmissionState.Init);
        assertThat(agent.getProtocolId()).isEqualTo(100);
        assertThat(agent.isDone()).isFalse();
        assertThat(agent.getQueueSize()).isEqualTo(0);
        assertThat(agent.getNegotiatedChainIds()).isNull();
    }

    @Test
    void testClientHasAgencyInInit() {
        assertThat(agent.hasAgency()).isTrue();

        // buildNextMessage should return MsgInit carrying our chains
        var msg = agent.buildNextMessage();
        assertThat(msg).isInstanceOf(MsgInit.class);
        assertThat(((MsgInit) msg).getChainIds()).containsExactly(CHAIN);
    }

    @Test
    void testInitTransitionsToInitAckThenIdle() {
        // Client sends MsgInit → InitAck (server agency)
        agent.sendRequest(new MsgInit(List.of(CHAIN)));
        assertThat(agent.getCurrentState()).isEqualTo(AppMsgSubmissionState.InitAck);
        assertThat(agent.hasAgency()).isFalse();

        // Server replies MsgInitAck → Idle, chains negotiated
        agent.receiveResponse(new MsgInitAck(List.of(CHAIN, "other-chain")));
        assertThat(agent.getCurrentState()).isEqualTo(AppMsgSubmissionState.Idle);
        assertThat(agent.hasAgency()).isFalse();
        assertThat(agent.getNegotiatedChainIds()).containsExactly(CHAIN);
    }

    @Test
    void testNoSharedChains_negotiatedEmpty_queueDrained() {
        agent.enqueueMessage(AppMsgTestFixtures.message(1, "hello"));
        assertThat(agent.getQueueSize()).isEqualTo(1);

        agent.sendRequest(new MsgInit(List.of(CHAIN)));
        agent.receiveResponse(new MsgInitAck(List.of("unrelated-chain")));

        assertThat(agent.getNegotiatedChainIds()).isEmpty();
        assertThat(agent.getQueueSize()).isEqualTo(0); // queued messages for unshared chains dropped
    }

    @Test
    void testEnqueueMessage() {
        AppMessage msg = AppMsgTestFixtures.message(1, "m1");
        assertThat(agent.enqueueMessage(msg)).isTrue();
        assertThat(agent.getQueueSize()).isEqualTo(1);
    }

    @Test
    void testEnqueueDuplicateRejected() {
        AppMessage msg1 = AppMsgTestFixtures.message(1, "same");
        AppMessage msg2 = AppMsgTestFixtures.message(1, "same"); // identical content — same ID
        assertThat(agent.enqueueMessage(msg1)).isTrue();
        assertThat(agent.enqueueMessage(msg2)).isFalse();
        assertThat(agent.getQueueSize()).isEqualTo(1);
    }

    @Test
    void testEnqueueOverflowRejected() {
        AppMsgSubmissionAgent smallAgent = new AppMsgSubmissionAgent(configFor(CHAIN), 2);
        assertThat(smallAgent.enqueueMessage(AppMsgTestFixtures.message(1, "a"))).isTrue();
        assertThat(smallAgent.enqueueMessage(AppMsgTestFixtures.message(2, "b"))).isTrue();
        assertThat(smallAgent.enqueueMessage(AppMsgTestFixtures.message(3, "c"))).isFalse();
        assertThat(smallAgent.getQueueSize()).isEqualTo(2);
    }

    @Test
    void testEnqueueExpiredRejected() {
        long past = System.currentTimeMillis() / 1000 - 10;
        AppMessage expired = AppMsgTestFixtures.message(CHAIN, "", 1, new byte[]{1}, past);
        assertThat(agent.enqueueMessage(expired)).isFalse();
    }

    @Test
    void testEnqueueOversizedRejected() {
        AppMsgSubmissionConfig config = AppMsgSubmissionConfig.builder()
                .chainIds(Set.of(CHAIN))
                .maxMessageSize(4)
                .build();
        AppMsgSubmissionAgent smallAgent = new AppMsgSubmissionAgent(config, 10);
        AppMessage tooBig = AppMsgTestFixtures.message(CHAIN, "", 1, new byte[]{1, 2, 3, 4, 5});
        assertThat(smallAgent.enqueueMessage(tooBig)).isFalse();
    }

    @Test
    void testEnqueueUnknownChainRejected() {
        AppMessage otherChain = AppMsgTestFixtures.message("unknown-chain", "", 1, new byte[]{1});
        assertThat(agent.enqueueMessage(otherChain)).isFalse();
    }

    @Test
    void testEnqueueUnsharedChainRejectedAfterNegotiation() {
        AppMsgSubmissionAgent multi = new AppMsgSubmissionAgent(configFor(CHAIN, "chain-b"), 10);
        multi.sendRequest(new MsgInit(List.of(CHAIN, "chain-b")));
        multi.receiveResponse(new MsgInitAck(List.of(CHAIN))); // peer serves only CHAIN

        assertThat(multi.enqueueMessage(AppMsgTestFixtures.message(CHAIN, "", 1, new byte[]{1}))).isTrue();
        assertThat(multi.enqueueMessage(AppMsgTestFixtures.message("chain-b", "", 2, new byte[]{2}))).isFalse();
    }

    @Test
    void testRequestMessageIdsFlow() {
        // Enqueue messages
        agent.enqueueMessage(AppMsgTestFixtures.message(1, "m1"));
        agent.enqueueMessage(AppMsgTestFixtures.message(2, "m2"));

        completeInit(CHAIN);

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
    void testRequestMessageIdsRespectsRequestWindow() {
        agent.enqueueMessage(AppMsgTestFixtures.message(1, "m1"));
        agent.enqueueMessage(AppMsgTestFixtures.message(2, "m2"));
        agent.enqueueMessage(AppMsgTestFixtures.message(3, "m3"));
        completeInit(CHAIN);

        agent.receiveResponse(new MsgRequestMessageIds(true, (short) 0, (short) 2));

        var reply = agent.buildNextMessage();

        assertThat(reply).isInstanceOf(MsgReplyMessageIds.class);
        assertThat(((MsgReplyMessageIds) reply).getMessageIds()).hasSize(2);
    }

    @Test
    void testBlockingEnqueueDoesNotExceedRequestWindow() {
        completeInit(CHAIN);
        agent.receiveResponse(new MsgRequestMessageIds(true, (short) 0, (short) 1));

        assertThat(agent.enqueueMessage(AppMsgTestFixtures.message(1, "m1"))).isTrue();
        assertThat(agent.enqueueMessage(AppMsgTestFixtures.message(2, "m2"))).isTrue();

        var reply = agent.buildNextMessage();

        assertThat(reply).isInstanceOf(MsgReplyMessageIds.class);
        assertThat(((MsgReplyMessageIds) reply).getMessageIds()).hasSize(1);
    }

    @Test
    void testRequestMessagesFlow() {
        AppMessage queued = AppMsgTestFixtures.message(CHAIN, "orders", 1, new byte[]{7});
        agent.enqueueMessage(queued);
        completeInit(CHAIN);

        // Server requests IDs
        agent.receiveResponse(new MsgRequestMessageIds(true, (short) 0, (short) 5));
        agent.sendRequest(agent.buildNextMessage()); // Send ReplyMessageIds

        // Server requests message bodies
        agent.receiveResponse(new MsgRequestMessages(List.of(queued.getMessageId())));
        assertThat(agent.getCurrentState()).isEqualTo(AppMsgSubmissionState.Messages);

        var reply = agent.buildNextMessage();
        assertThat(reply).isInstanceOf(MsgReplyMessages.class);
        MsgReplyMessages replyMsgs = (MsgReplyMessages) reply;
        assertThat(replyMsgs.getMessages()).hasSize(1);
        assertThat(replyMsgs.getMessages().get(0).getTopic()).isEqualTo("orders");
    }

    @Test
    void testAcknowledgmentRemovesFromQueue() {
        agent.enqueueMessage(AppMsgTestFixtures.message(1, "m1"));
        agent.enqueueMessage(AppMsgTestFixtures.message(2, "m2"));
        completeInit(CHAIN);

        // First request
        agent.receiveResponse(new MsgRequestMessageIds(true, (short) 0, (short) 5));
        agent.sendRequest(agent.buildNextMessage());

        // Ack 1, request more
        agent.receiveResponse(new MsgRequestMessageIds(false, (short) 1, (short) 5));
        assertThat(agent.getQueueSize()).isEqualTo(1); // One was ack'd and removed
    }

    @Test
    void testResetClearsState() {
        agent.enqueueMessage(AppMsgTestFixtures.message(1, "m1"));
        completeInit(CHAIN);

        agent.reset();

        assertThat(agent.getCurrentState()).isEqualTo(AppMsgSubmissionState.Init);
        assertThat(agent.getQueueSize()).isEqualTo(0);
        assertThat(agent.getNegotiatedChainIds()).isNull();
    }
}
