package com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n;

import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.AppMsgTestFixtures;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessageId;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.messages.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.bloxbean.cardano.yaci.core.protocol.appmsg.AppMsgTestFixtures.CHAIN;
import static org.assertj.core.api.Assertions.assertThat;

/** ADR 0019: configurable window, chunked body requests, priority lane, batch validation. */
class AppMsgSubmissionThroughputTest {

    // ---------------------------------------------------------------- server

    @Test
    void serverRequestsTheConfiguredWindowAboveTen() {
        AppMsgSubmissionServerAgent server = server(AppMsgSubmissionConfig.builder()
                .batchSize(256).maxUnacknowledged(200).build());
        MsgRequestMessageIds request = (MsgRequestMessageIds) server.buildNextMessage();
        assertThat(request.getReqCount()).isEqualTo((short) 200);
    }

    @Test
    void serverDefaultWindowStaysTen() {
        AppMsgSubmissionServerAgent server = server(AppMsgSubmissionConfig.createDefault());
        MsgRequestMessageIds request = (MsgRequestMessageIds) server.buildNextMessage();
        assertThat(request.getReqCount()).isEqualTo((short) 10);
    }

    @Test
    void serverRequestsBodiesInByteBudgetChunksAndAcknowledgesAll() {
        List<AppMessage> delivered = new CopyOnWriteArrayList<>();
        AppMsgSubmissionServerAgent server = server(AppMsgSubmissionConfig.builder()
                .batchSize(50).maxUnacknowledged(50).maxBodiesBytesPerRequest(250).build());
        server.addListener(new AppMsgSubmissionListener() {
            @Override
            public void handleReplyMessages(MsgReplyMessages reply) {
                delivered.addAll(reply.getMessages());
            }
        });
        send(server, server.buildNextMessage()); // RequestMessageIds

        List<AppMessage> messages = new ArrayList<>();
        MsgReplyMessageIds ids = new MsgReplyMessageIds();
        for (int i = 1; i <= 7; i++) {
            AppMessage m = AppMsgTestFixtures.message(CHAIN, "t", i, new byte[100]);
            messages.add(m);
            ids.addMessageId(new AppMessageId(m.getMessageId(), 100));
        }
        server.receiveResponse(ids);

        int chunks = 0;
        Message next = server.buildNextMessage();
        while (next instanceof MsgRequestMessages) {
            MsgRequestMessages bodies = (MsgRequestMessages) next;
            assertThat(bodies.getMessageIds()).hasSizeBetween(1, 2);
            send(server, bodies);
            MsgReplyMessages reply = new MsgReplyMessages();
            for (byte[] id : bodies.getMessageIds()) {
                messages.stream().filter(m -> java.util.Arrays.equals(m.getMessageId(), id))
                        .findFirst().ifPresent(reply::addMessage);
            }
            server.receiveResponse(reply);
            chunks++;
            next = server.buildNextMessage();
        }

        assertThat(chunks).isEqualTo(4);
        assertThat(delivered).extracting(AppMessage::getSenderSeq).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L);
        assertThat(next).isInstanceOf(MsgRequestMessageIds.class);
        assertThat(((MsgRequestMessageIds) next).getAckCount()).isEqualTo((short) 7);
    }

    @Test
    void serverRequestsOneOversizedBodyAlone() {
        AppMsgSubmissionServerAgent server = server(AppMsgSubmissionConfig.builder()
                .batchSize(10).maxBodiesBytesPerRequest(50).build());
        send(server, server.buildNextMessage());
        AppMessage big = AppMsgTestFixtures.message(CHAIN, "t", 1, new byte[500]);
        MsgReplyMessageIds ids = new MsgReplyMessageIds();
        ids.addMessageId(new AppMessageId(big.getMessageId(), 500));
        server.receiveResponse(ids);

        MsgRequestMessages bodies = (MsgRequestMessages) server.buildNextMessage();
        assertThat(bodies.getMessageIds()).hasSize(1);
    }

    @Test
    void validateAllRunsOncePerReplyAndKeepsOrder() {
        List<Integer> batchSizes = new CopyOnWriteArrayList<>();
        AppMsgValidator rejectEven = new AppMsgValidator() {
            @Override
            public Result validate(AppMessage message) {
                throw new AssertionError("validateAll must be used");
            }

            @Override
            public List<Result> validateAll(List<AppMessage> messages) {
                batchSizes.add(messages.size());
                return messages.stream().map(m -> m.getSenderSeq() % 2 == 0
                        ? Result.reject("even") : Result.accept()).toList();
            }
        };
        List<AppMessage> delivered = deliverThrough(rejectEven, 5);
        assertThat(batchSizes).containsExactly(5);
        assertThat(delivered).extracting(AppMessage::getSenderSeq).containsExactly(1L, 3L, 5L);
    }

    @Test
    void validateAllWithWrongResultCountRejectsTheReply() {
        AppMsgValidator broken = new AppMsgValidator() {
            @Override
            public Result validate(AppMessage message) {
                return Result.accept();
            }

            @Override
            public List<Result> validateAll(List<AppMessage> messages) {
                return List.of(Result.accept());
            }
        };
        assertThat(deliverThrough(broken, 3)).isEmpty();
    }

    // ---------------------------------------------------------------- client

    @Test
    void clientOffersPriorityMessagesFirstAndAcksStayFifo() {
        AppMsgSubmissionAgent client = client(AppMsgSubmissionConfig.builder()
                .chainIds(Set.of(CHAIN))
                .priority(m -> m.getTopic().startsWith("~"))
                .build(), 100);
        AppMessage o1 = AppMsgTestFixtures.message(CHAIN, "user", 1, new byte[]{1});
        AppMessage o2 = AppMsgTestFixtures.message(CHAIN, "user", 2, new byte[]{2});
        AppMessage p1 = AppMsgTestFixtures.message(CHAIN, "~consensus/commit", 3, new byte[]{3});
        client.enqueueMessage(o1);
        client.enqueueMessage(o2);
        client.enqueueMessage(p1);

        client.receiveResponse(new MsgRequestMessageIds(true, (short) 0, (short) 2));
        MsgReplyMessageIds reply = (MsgReplyMessageIds) client.buildNextMessage();
        assertThat(reply.getMessageIds()).extracting(AppMessageId::getMessageId)
                .containsExactly(p1.getMessageId(), o1.getMessageId());
        client.sendRequest(reply);

        client.receiveResponse(new MsgRequestMessages(List.of(p1.getMessageId(), o1.getMessageId())));
        MsgReplyMessages bodies = (MsgReplyMessages) client.buildNextMessage();
        assertThat(bodies.getMessages()).containsExactly(p1, o1);
        client.sendRequest(bodies);

        client.receiveResponse(new MsgRequestMessageIds(true, (short) 2, (short) 2));
        MsgReplyMessageIds second = (MsgReplyMessageIds) client.buildNextMessage();
        assertThat(second.getMessageIds()).extracting(AppMessageId::getMessageId)
                .containsExactly(o2.getMessageId());
        assertThat(client.getQueueSize()).isEqualTo(1);
    }

    @Test
    void fullOrdinaryLaneNeverRejectsPriorityMessages() {
        AppMsgSubmissionAgent client = client(AppMsgSubmissionConfig.builder()
                .chainIds(Set.of(CHAIN))
                .priority(m -> m.getTopic().startsWith("~"))
                .maxPendingPriorityMessages(1)
                .build(), 2);
        assertThat(client.enqueueMessage(AppMsgTestFixtures.message(CHAIN, "user", 1, new byte[]{1}))).isTrue();
        assertThat(client.enqueueMessage(AppMsgTestFixtures.message(CHAIN, "user", 2, new byte[]{2}))).isTrue();
        assertThat(client.enqueueMessage(AppMsgTestFixtures.message(CHAIN, "user", 3, new byte[]{3}))).isFalse();
        assertThat(client.enqueueMessage(AppMsgTestFixtures.message(CHAIN, "~consensus/vote", 4, new byte[]{4})))
                .isTrue();
        assertThat(client.enqueueMessage(AppMsgTestFixtures.message(CHAIN, "~consensus/vote", 5, new byte[]{5})))
                .isFalse();
        assertThat(client.getRejectedQueueFullCount()).isEqualTo(2);
        assertThat(client.getQueueSize()).isEqualTo(3);
        assertThat(client.getPriorityQueueSize()).isEqualTo(1);
    }

    @Test
    void clientHandlesThousandsOfPendingMessagesAndLargeWindows() {
        AppMsgSubmissionAgent client = client(AppMsgSubmissionConfig.builder()
                .chainIds(Set.of(CHAIN)).maxPendingMessages(20_000).build(), 20_000);
        for (int i = 1; i <= 20_000; i++) {
            assertThat(client.enqueueMessage(AppMsgTestFixtures.message(CHAIN, "t", i, new byte[]{(byte) i})))
                    .isTrue();
        }
        assertThat(client.enqueueMessage(AppMsgTestFixtures.message(CHAIN, "t", 7, new byte[]{7}))).isFalse();

        int ack = 0;
        long next = 1;
        while (client.getQueueSize() > 0) {
            client.receiveResponse(new MsgRequestMessageIds(true, (short) ack, (short) 500));
            MsgReplyMessageIds ids = (MsgReplyMessageIds) client.buildNextMessage();
            client.sendRequest(ids);
            List<byte[]> wanted = ids.getMessageIds().stream().map(AppMessageId::getMessageId).toList();
            client.receiveResponse(new MsgRequestMessages(wanted));
            MsgReplyMessages bodies = (MsgReplyMessages) client.buildNextMessage();
            client.sendRequest(bodies);
            for (AppMessage m : bodies.getMessages()) {
                assertThat(m.getSenderSeq()).isEqualTo(next++);
            }
            ack = bodies.getMessages().size();
        }
        assertThat(next).isEqualTo(20_001);
    }

    @Test
    void clientNeverBuildsASecondReplyBeforeTheNextRequest() {
        AppMsgSubmissionAgent client = client(AppMsgSubmissionConfig.builder()
                .chainIds(Set.of(CHAIN)).build(), 10);
        client.enqueueMessage(AppMsgTestFixtures.message(1, "m1"));
        client.receiveResponse(new MsgRequestMessageIds(true, (short) 0, (short) 5));
        assertThat(client.buildNextMessage()).isInstanceOf(MsgReplyMessageIds.class);
        // The write has not completed, so the state is still MessageIdsBlocking: a second wake must not resend.
        client.enqueueMessage(AppMsgTestFixtures.message(2, "m2"));
        assertThat(client.buildNextMessage()).isNull();
    }

    @Test
    void offeredMessagesAreDeliveredEvenAfterTheyExpire() throws Exception {
        AppMsgSubmissionAgent client = client(AppMsgSubmissionConfig.builder()
                .chainIds(Set.of(CHAIN)).build(), 10);
        long soon = System.currentTimeMillis() / 1000 + 1;
        AppMessage shortLived = AppMsgTestFixtures.message(CHAIN, "t", 1, new byte[]{1}, soon);
        client.enqueueMessage(shortLived);
        client.receiveResponse(new MsgRequestMessageIds(true, (short) 0, (short) 5));
        client.sendRequest(client.buildNextMessage());
        Thread.sleep(2100);

        client.receiveResponse(new MsgRequestMessages(List.of(shortLived.getMessageId())));
        MsgReplyMessages bodies = (MsgReplyMessages) client.buildNextMessage();
        assertThat(bodies.getMessages()).containsExactly(shortLived);
    }

    @Test
    void priorityIdsTakeAtMostHalfTheWindowWhileOrdinaryMessagesWait() {
        AppMsgSubmissionAgent client = client(AppMsgSubmissionConfig.builder()
                .chainIds(Set.of(CHAIN))
                .priority(m -> m.getTopic().startsWith("~"))
                .build(), 100);
        for (int i = 1; i <= 10; i++) {
            client.enqueueMessage(AppMsgTestFixtures.message(CHAIN, "~consensus/vote", i, new byte[]{(byte) i}));
        }
        for (int i = 11; i <= 12; i++) {
            client.enqueueMessage(AppMsgTestFixtures.message(CHAIN, "user", i, new byte[]{(byte) i, 0}));
        }
        client.receiveResponse(new MsgRequestMessageIds(true, (short) 0, (short) 6));
        MsgReplyMessageIds reply = (MsgReplyMessageIds) client.buildNextMessage();
        // 3 priority (half of 6), 2 ordinary, then 1 more priority to fill the window
        assertThat(reply.getMessageIds()).hasSize(6);
        assertThat(reply.getMessageIds()).extracting(AppMessageId::getSize).containsExactly(1, 1, 1, 2, 2, 1);
    }

    @Test
    void priorityLaneIsBoundedByBytes() {
        AppMsgSubmissionAgent client = client(AppMsgSubmissionConfig.builder()
                .chainIds(Set.of(CHAIN))
                .priority(m -> m.getTopic().startsWith("~"))
                .maxPendingPriorityBytes(250)
                .build(), 100);
        assertThat(client.enqueueMessage(AppMsgTestFixtures.message(CHAIN, "~consensus/propose", 1, new byte[200])))
                .isTrue();
        assertThat(client.enqueueMessage(AppMsgTestFixtures.message(CHAIN, "~consensus/propose", 2, new byte[100])))
                .isFalse();
        assertThat(client.enqueueMessage(AppMsgTestFixtures.message(CHAIN, "~consensus/vote", 3, new byte[50])))
                .isTrue();
    }

    // ---------------------------------------------------------------- helpers

    private static List<AppMessage> deliverThrough(AppMsgValidator validator, int count) {
        List<AppMessage> delivered = new CopyOnWriteArrayList<>();
        AppMsgSubmissionServerAgent server = server(AppMsgSubmissionConfig.builder()
                .batchSize(count).maxUnacknowledged(count).validator(validator).build());
        server.addListener(new AppMsgSubmissionListener() {
            @Override
            public void handleReplyMessages(MsgReplyMessages reply) {
                delivered.addAll(reply.getMessages());
            }
        });
        send(server, server.buildNextMessage());
        MsgReplyMessageIds ids = new MsgReplyMessageIds();
        MsgReplyMessages bodies = new MsgReplyMessages();
        for (int i = 1; i <= count; i++) {
            AppMessage m = AppMsgTestFixtures.message(CHAIN, "t", i, new byte[]{(byte) i});
            ids.addMessageId(new AppMessageId(m.getMessageId(), m.getSize()));
            bodies.addMessage(m);
        }
        server.receiveResponse(ids);
        send(server, server.buildNextMessage());
        server.receiveResponse(bodies);
        return delivered;
    }

    private static AppMsgSubmissionServerAgent server(AppMsgSubmissionConfig config) {
        AppMsgSubmissionServerAgent server = new AppMsgSubmissionServerAgent(config);
        server.receiveResponse(new MsgInit(List.of(CHAIN)));
        send(server, server.buildNextMessage()); // MsgInitAck → Idle, queues the first request
        return server;
    }

    private static AppMsgSubmissionAgent client(AppMsgSubmissionConfig config, int capacity) {
        AppMsgSubmissionAgent client = new AppMsgSubmissionAgent(config, capacity);
        client.sendRequest(new MsgInit(List.of(CHAIN)));
        client.receiveResponse(new MsgInitAck(List.of(CHAIN)));
        return client;
    }

    private static void send(AppMsgSubmissionServerAgent server, Message message) {
        assertThat(message).isNotNull();
        server.sendRequest(message);
    }
}
