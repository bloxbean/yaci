package com.bloxbean.cardano.yaci.core.protocol.chainsync;

import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yaci.core.protocol.Agent;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.Segment;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.*;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.n2n.ChainSyncServerAgent;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.n2n.ChainSyncState;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import io.netty.channel.*;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Modifier;
import java.net.SocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency tests for ChainSyncServerAgent to verify:
 * - No block gaps after reconnection (Bug 3 fix)
 * - Thread-safe pendingResponses queue (Bug 1 fix)
 * - Volatile visibility of shared fields (Bug 2 fix)
 * - Synchronized onNewDataAvailable prevents interleaving (Bug 4 fix)
 */
class ChainSyncServerAgentConcurrencyTest {

    private static final int AWAIT_REPLY = 1;
    private static final int ROLL_FORWARD = 2;
    private static final int ROLL_BACKWARD = 3;
    private static final int INTERSECT_FOUND = 5;

    private ChainSyncServerAgent agent;
    private FakeChainState chainState;
    private StubChannel channel;

    @BeforeEach
    void setup() {
        chainState = new FakeChainState();
        agent = new ChainSyncServerAgent(chainState);
        channel = new StubChannel();
        agent.setChannel(channel);
    }

    /**
     * Core scenario: client synced to block 116, server has produced up to block 120.
     * onNewDataAvailable() must deliver blocks 117, 118, 119, 120 sequentially — never skip to 120.
     */
    @Test
    void onNewDataAvailable_shouldDeliverBlocksSequentially_noGaps() {
        populateChain(100, 120);

        Point block116 = chainState.pointAt(116);
        performFindIntersect(block116);
        performRequestNextExpectingRollbackward(block116);

        for (int i = 117; i <= 120; i++) {
            performRequestNextExpectingRollForward(i);
        }

        performRequestNextExpectingAwaitReply();

        assertThat(agent.isClientAtTip()).isTrue();
        assertThat(agent.getLastSentPoint()).isEqualTo(chainState.pointAt(120));
    }

    /**
     * Simulates the reconnection gap scenario:
     * - Client was at block 116 when disconnected
     * - Server produced blocks 117-120 while client was offline
     * - Client reconnects, finds intersection at 116
     * - onNewDataAvailable() is called — must send 117, not 120
     */
    @Test
    void onNewDataAvailable_afterReconnection_shouldSendNextSequentialBlock() {
        populateChain(100, 120);

        Point block116 = chainState.pointAt(116);
        performFindIntersect(block116);
        performRequestNextExpectingRollbackward(block116);

        performRequestNextExpectingRollForward(117);
        performRequestNextExpectingRollForward(118);
        performRequestNextExpectingRollForward(119);
        performRequestNextExpectingRollForward(120);

        performRequestNextExpectingAwaitReply();
        assertThat(agent.isClientAtTip()).isTrue();

        // New block 121 arrives
        chainState.addBlock(121);
        agent.onNewDataAvailable();

        assertThat(agent.getLastSentPoint()).isEqualTo(chainState.pointAt(121));
    }

    /**
     * Multiple rapid onNewDataAvailable calls with one request parked at the tip.
     * The first answers it; later calls have nothing to answer until the next RequestNext.
     */
    @Test
    void onNewDataAvailable_multipleCalls_answerOnlyTheParkedRequest() {
        populateChain(100, 110);
        driveClientToTip(110);

        chainState.addBlock(111);
        chainState.addBlock(112);
        chainState.addBlock(113);

        agent.onNewDataAvailable();
        assertThat(agent.getLastSentPoint()).isEqualTo(chainState.pointAt(111));
        assertThat(agent.isClientAtTip()).isFalse();

        // No request is parked any more, so later calls send nothing
        agent.onNewDataAvailable();
        assertThat(agent.getLastSentPoint()).isEqualTo(chainState.pointAt(111));
    }

    /**
     * Concurrent onNewDataAvailable from multiple block-producer threads.
     * Only one should win; no exceptions, no corrupted state.
     */
    @Test
    void concurrentOnNewDataAvailable_noExceptionsOrCorruption() throws Exception {
        populateChain(100, 110);
        driveClientToTip(110);

        chainState.addBlock(111);

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger exceptionCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    agent.onNewDataAvailable();
                } catch (Exception e) {
                    exceptionCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(exceptionCount.get()).isZero();
        assertThat(agent.getLastSentPoint()).isEqualTo(chainState.pointAt(111));
    }

    /**
     * Concurrent onNewDataAvailable from multiple threads — no exceptions.
     */
    @Test
    void concurrentNotifyAndRequestNext_noExceptions() throws Exception {
        populateChain(100, 150);
        driveClientToTip(150);

        // Now add more blocks that concurrent threads will try to deliver
        for (int i = 151; i <= 200; i++) {
            chainState.addBlock(i);
        }

        int iterations = 50;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        AtomicInteger exceptionCount = new AtomicInteger(0);

        // Thread 1: block producer notifications
        executor.submit(() -> {
            try {
                startLatch.await();
                for (int i = 0; i < iterations; i++) {
                    agent.onNewDataAvailable();
                    Thread.yield();
                }
            } catch (Exception e) {
                exceptionCount.incrementAndGet();
            } finally {
                doneLatch.countDown();
            }
        });

        // Thread 2: also call onNewDataAvailable (simulating second caller)
        executor.submit(() -> {
            try {
                startLatch.await();
                for (int i = 0; i < iterations; i++) {
                    agent.onNewDataAvailable();
                    Thread.yield();
                }
            } catch (Exception e) {
                exceptionCount.incrementAndGet();
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(exceptionCount.get()).isZero();
        assertThat(agent.getCurrentState()).isNotNull();
    }

    /**
     * After reset(), all fields should be cleared and agent should be reusable.
     */
    @Test
    void reset_clearsAllState() {
        populateChain(100, 110);
        driveClientToTip(110);

        agent.reset();

        assertThat(agent.isClientAtTip()).isFalse();
        assertThat(agent.getLastSentPoint()).isNull();
        assertThat(agent.getCurrentState()).isEqualTo(ChainSyncState.Idle);
    }

    /**
     * Chain switches to a fork after block 112 while one request is parked at tip 115. The parked request gets the
     * Rollbackward, and the client then follows the new fork. The rollback lands on 105, not 112, because
     * findRollbackPoint steps back 10 slots at a time once the last sent point is gone.
     */
    @Test
    void forkSwitch_onParkedRequest_rollsBackThenFollowsNewFork() {
        populateChain(100, 115);
        deliverInbound(new FindIntersect(new Point[]{chainState.pointAt(115)}));
        deliverInbound(new RequestNext());
        deliverInbound(new RequestNext());

        chainState.switchForkAfter(112);
        populateChain(113, 116);
        agent.onNewDataAvailable();
        assertThat(channel.writtenReplies()).containsExactly("IF", "RB 115", "AW", "RB 105");

        channel.written.clear();
        for (int i = 106; i <= 117; i++)
            deliverInbound(new RequestNext());
        assertThat(channel.writtenReplies()).containsExactly(
                "RF 106/0", "RF 107/0", "RF 108/0", "RF 109/0", "RF 110/0", "RF 111/0", "RF 112/0",
                "RF 113/1", "RF 114/1", "RF 115/1", "RF 116/1", "AW");
        assertThat(agent.getLastSentPoint()).isEqualTo(chainState.pointAt(116));
    }

    /**
     * Several pipelined requests parked at the tip when the chain switches fork. The oldest gets the Rollbackward
     * and each later one the next block, so none is left unanswered.
     */
    @Test
    void forkSwitch_onPipelinedParkedRequests_answersEachInOrder() {
        populateChain(100, 115);
        deliverInbound(new FindIntersect(new Point[]{chainState.pointAt(115)}));
        deliverInbound(new RequestNext());
        deliverInbound(new RequestNext());
        deliverInbound(new RequestNext());
        deliverInbound(new RequestNext());

        chainState.switchForkAfter(112);
        populateChain(113, 116);
        agent.onNewDataAvailable();

        assertThat(channel.writtenReplies())
                .containsExactly("IF", "RB 115", "AW", "RB 105", "RF 106/0", "RF 107/0");
        assertThat(agent.getCurrentState()).isEqualTo(ChainSyncState.Idle);
    }

    /**
     * The chain rolls back below the last sent block with no new blocks yet (tip slot < last sent slot).
     */
    @Test
    void rollbackWithoutNewBlocks_answersParkedRequests() {
        populateChain(100, 115);
        deliverInbound(new FindIntersect(new Point[]{chainState.pointAt(115)}));
        deliverInbound(new RequestNext());
        deliverInbound(new RequestNext());
        deliverInbound(new RequestNext());

        chainState.rollbackTo(112L);
        agent.onNewDataAvailable();

        assertThat(channel.writtenReplies()).containsExactly("IF", "RB 115", "AW", "RB 105", "RF 106/0");
        assertThat(agent.getCurrentState()).isEqualTo(ChainSyncState.Idle);
    }

    /**
     * Stress test: rapid block production with concurrent notifications.
     * Verifies no exceptions under sustained concurrent access.
     */
    @Test
    void stressTest_rapidBlockProductionWithConcurrentNotifications() throws Exception {
        populateChain(1, 100);
        driveClientToTip(100);

        int newBlocks = 50;
        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch doneLatch = new CountDownLatch(3);
        AtomicInteger exceptionCount = new AtomicInteger(0);

        executor.submit(() -> {
            try {
                for (int i = 101; i <= 100 + newBlocks; i++) {
                    chainState.addBlock(i);
                    Thread.sleep(1);
                }
            } catch (Exception e) {
                exceptionCount.incrementAndGet();
            } finally {
                doneLatch.countDown();
            }
        });

        executor.submit(() -> {
            try {
                for (int i = 0; i < newBlocks * 3; i++) {
                    agent.onNewDataAvailable();
                    Thread.yield();
                }
            } catch (Exception e) {
                exceptionCount.incrementAndGet();
            } finally {
                doneLatch.countDown();
            }
        });

        executor.submit(() -> {
            try {
                for (int i = 0; i < newBlocks * 3; i++) {
                    agent.onNewDataAvailable();
                    Thread.yield();
                }
            } catch (Exception e) {
                exceptionCount.incrementAndGet();
            } finally {
                doneLatch.countDown();
            }
        });

        doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(exceptionCount.get()).isZero();
        assertThat(agent.getLastSentPoint()).isNotNull();
        assertThat(agent.getLastSentPoint().getSlot()).isGreaterThan(100L);
    }

    /**
     * A pipelining client parks several RequestNext at the tip. Each must get exactly one reply, in order,
     * with at most one AwaitReply outstanding at a time.
     */
    @Test
    void pipelinedRequestsAtTip_eachGetsExactlyOneReply() {
        populateChain(100, 110);
        Point tip = chainState.pointAt(110);
        deliverInbound(new FindIntersect(new Point[]{tip}));
        deliverInbound(new RequestNext());
        channel.written.clear();

        deliverInbound(new RequestNext());
        deliverInbound(new RequestNext());
        deliverInbound(new RequestNext());
        assertThat(channel.writtenMessageIds()).containsExactly(AWAIT_REPLY);
        assertThat(agent.getCurrentState()).isEqualTo(ChainSyncState.MustReply);

        chainState.addBlock(111);
        agent.onNewDataAvailable();
        assertThat(channel.writtenMessageIds()).containsExactly(AWAIT_REPLY, ROLL_FORWARD, AWAIT_REPLY);

        chainState.addBlock(112);
        chainState.addBlock(113);
        agent.onNewDataAvailable();
        assertThat(channel.writtenMessageIds())
                .containsExactly(AWAIT_REPLY, ROLL_FORWARD, AWAIT_REPLY, ROLL_FORWARD, ROLL_FORWARD);
        assertThat(agent.getLastSentPoint()).isEqualTo(chainState.pointAt(113));
        assertThat(agent.getCurrentState()).isEqualTo(ChainSyncState.Idle);

        // Nothing is owed any more, so a new block waits for the next RequestNext
        chainState.addBlock(114);
        agent.onNewDataAvailable();
        assertThat(channel.writtenMessageIds()).hasSize(5);
    }

    /**
     * Pipelined RequestNext read before the previous reply's write completed must not strand that reply
     * in the queue: every request gets its RollForward without waiting for another inbound message.
     */
    @Test
    void pipelinedRequestsBeforeWriteCompletion_noReplyStranded() {
        populateChain(100, 110);
        Point from = chainState.pointAt(100);
        deliverInbound(new FindIntersect(new Point[]{from}));
        deliverInbound(new RequestNext());
        channel.written.clear();
        channel.holdWriteCompletion();

        deliverInbound(new RequestNext());
        deliverInbound(new RequestNext());
        deliverInbound(new RequestNext());

        assertThat(channel.writtenMessageIds()).containsExactly(ROLL_FORWARD, ROLL_FORWARD, ROLL_FORWARD);
        assertThat(agent.getLastSentPoint()).isEqualTo(chainState.pointAt(103));
        assertThat(agent.hasAgency()).isFalse();
    }

    /**
     * Replies queued from a block-producer thread are written on the channel's event loop, in queue order,
     * so they cannot be overtaken by a reply written inline by the inbound handler.
     */
    @Test
    void repliesQueuedOffTheEventLoop_areWrittenOnTheEventLoopInOrder() throws Exception {
        DefaultEventLoop loop = new DefaultEventLoop();
        try {
            channel.useEventLoop(loop);
            populateChain(100, 110);
            deliverInbound(new FindIntersect(new Point[]{chainState.pointAt(110)}));
            deliverInbound(new RequestNext());
            deliverInbound(new RequestNext());

            chainState.addBlock(111);
            agent.onNewDataAvailable();
            loop.submit(() -> { }).sync();

            assertThat(channel.writtenMessageIds())
                    .containsExactly(INTERSECT_FOUND, ROLL_BACKWARD, AWAIT_REPLY, ROLL_FORWARD);
            assertThat(channel.writerThreads).allMatch(loop::inEventLoop);
        } finally {
            loop.shutdownGracefully().sync();
        }
    }

    /**
     * A drain scheduled before the client's Done runs after it. It must not write or turn Done back into Idle.
     */
    @Test
    void drainScheduledBeforeDone_keepsAgentDone() throws Exception {
        DefaultEventLoop loop = new DefaultEventLoop();
        try {
            channel.useEventLoop(loop);
            populateChain(100, 110);
            deliverInbound(new FindIntersect(new Point[]{chainState.pointAt(110)}));
            deliverInbound(new RequestNext());
            deliverInbound(new RequestNext());
            deliverInbound(new RequestNext());
            loop.submit(() -> { }).sync();

            CountDownLatch release = new CountDownLatch(1);
            loop.execute(() -> {
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            chainState.addBlock(111);
            agent.onNewDataAvailable();
            chainState.addBlock(112);
            agent.onNewDataAvailable();
            loop.execute(() -> agent.receiveResponse(new ChainSyncMsgDone()));
            loop.execute(agent::sendNextMessage);
            release.countDown();
            loop.submit(() -> { }).sync();

            assertThat(agent.isDone()).isTrue();
            assertThat(channel.writtenMessageIds())
                    .containsExactly(INTERSECT_FOUND, ROLL_BACKWARD, AWAIT_REPLY, ROLL_FORWARD, AWAIT_REPLY, ROLL_FORWARD);

            chainState.addBlock(113);
            agent.onNewDataAvailable();
            loop.submit(() -> { }).sync();
            assertThat(agent.isDone()).isTrue();
            assertThat(channel.writtenMessageIds()).hasSize(6);
        } finally {
            loop.shutdownGracefully().sync();
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    void notifyNewBlock_answersParkedRequest() {
        populateChain(100, 110);
        deliverInbound(new FindIntersect(new Point[]{chainState.pointAt(110)}));
        deliverInbound(new RequestNext());
        deliverInbound(new RequestNext());
        channel.written.clear();

        chainState.addBlock(111);
        agent.notifyNewBlock(chainState.pointAt(111));

        assertThat(channel.writtenMessageIds()).containsExactly(ROLL_FORWARD);
        assertThat(agent.getLastSentPoint()).isEqualTo(chainState.pointAt(111));
    }

    @Test
    void firstRequestAfterIntersect_isAnsweredWithRollbackward() {
        populateChain(100, 110);
        deliverInbound(new FindIntersect(new Point[]{chainState.pointAt(105)}));
        channel.written.clear();

        deliverInbound(new RequestNext());
        deliverInbound(new RequestNext());

        assertThat(channel.writtenMessageIds()).containsExactly(ROLL_BACKWARD, ROLL_FORWARD);
        assertThat(agent.getLastSentPoint()).isEqualTo(chainState.pointAt(106));
    }

    @Test
    void agentStateUpdatesAreVisibleAcrossNettyAndProducerThreads() throws Exception {
        var currentState = Agent.class.getDeclaredField("currentState");
        assertThat(Modifier.isVolatile(currentState.getModifiers())).isTrue();

        var sendRequest = Agent.class.getDeclaredMethod("sendRequest", Message.class);
        assertThat(Modifier.isSynchronized(sendRequest.getModifiers())).isTrue();
    }

    /**
     * ADR 0013 D3: an out-of-order intersection abandons parked requests, even after AwaitReply was sent.
     * This characterizes permissive peer handling; the sequence is not protocol-legal.
     */
    @Test
    void findIntersectWithParkedRequests_afterAwaitReplyDrains_discardsOldObligations() {
        populateChain(100, 110);
        deliverInbound(new FindIntersect(new Point[]{chainState.pointAt(110)}));
        deliverInbound(new RequestNext());
        deliverInbound(new RequestNext());
        deliverInbound(new RequestNext());
        assertThat(channel.writtenMessageIds()).containsExactly(INTERSECT_FOUND, ROLL_BACKWARD, AWAIT_REPLY);
        assertThat(agent.getCurrentState()).isEqualTo(ChainSyncState.MustReply);
        channel.written.clear();

        agent.receiveResponse(new FindIntersect(new Point[]{chainState.pointAt(105)}));
        assertThat(agent.getCurrentState()).isEqualTo(ChainSyncState.Intersect);
        assertThat(agent.hasAgency()).isTrue();
        agent.sendNextMessage();
        assertThat(channel.writtenMessageIds()).containsExactly(INTERSECT_FOUND);
        assertThat(agent.getCurrentState()).isEqualTo(ChainSyncState.Idle);

        chainState.addBlock(111);
        agent.onNewDataAvailable();
        chainState.addBlock(112);
        agent.onNewDataAvailable();
        assertThat(channel.writtenMessageIds()).containsExactly(INTERSECT_FOUND);
        assertThat(agent.getCurrentState()).isEqualTo(ChainSyncState.Idle);
        assertThat(agent.hasAgency()).isFalse();
    }

    /**
     * ADR 0013 D3: abandoning parked requests does not clear an older queued AwaitReply.
     * Defer the inbound handler's drain to observe the queue-head state and FIFO order.
     */
    @Test
    void findIntersectWithParkedRequests_beforeAwaitReplyDrains_preservesQueuedReply() {
        populateChain(100, 110);
        deliverInbound(new FindIntersect(new Point[]{chainState.pointAt(110)}));
        deliverInbound(new RequestNext());
        channel.written.clear();
        agent.receiveResponse(new RequestNext());
        agent.receiveResponse(new RequestNext());
        assertThat(agent.getCurrentState()).isEqualTo(ChainSyncState.CanAwait);
        assertThat(channel.written).isEmpty();

        agent.receiveResponse(new FindIntersect(new Point[]{chainState.pointAt(105)}));
        assertThat(agent.getCurrentState()).isEqualTo(ChainSyncState.CanAwait);
        assertThat(agent.hasAgency()).isTrue();
        assertThat(channel.written).isEmpty();
        agent.sendNextMessage();
        assertThat(channel.writtenMessageIds()).containsExactly(AWAIT_REPLY, INTERSECT_FOUND);
        assertThat(agent.getCurrentState()).isEqualTo(ChainSyncState.Idle);

        chainState.addBlock(111);
        agent.onNewDataAvailable();
        chainState.addBlock(112);
        agent.onNewDataAvailable();
        assertThat(channel.writtenMessageIds()).containsExactly(AWAIT_REPLY, INTERSECT_FOUND);
        assertThat(agent.getCurrentState()).isEqualTo(ChainSyncState.Idle);
        assertThat(agent.hasAgency()).isFalse();
    }

    // ---- Helper methods ----

    /** Same dispatch as MiniProtoServerInboundHandler: receive, then send if the server has agency. */
    private void deliverInbound(Message message) {
        agent.receiveResponse(message);
        if (agent.hasAgency())
            agent.sendNextMessage();
    }

    private void populateChain(int fromBlock, int toBlock) {
        for (int i = fromBlock; i <= toBlock; i++) {
            chainState.addBlock(i);
        }
    }

    private void driveClientToTip(int tipBlock) {
        Point tipPoint = chainState.pointAt(tipBlock);
        performFindIntersect(tipPoint);
        performRequestNextExpectingRollbackward(tipPoint);
        performRequestNextExpectingAwaitReply();
        assertThat(agent.isClientAtTip()).isTrue();
    }

    private void performFindIntersect(Point intersectionPoint) {
        FindIntersect findIntersect = new FindIntersect(new Point[]{intersectionPoint});
        agent.receiveResponse(findIntersect);
        assertThat(agent.getCurrentState()).isEqualTo(ChainSyncState.Intersect);
        agent.sendNextMessage();
        assertThat(agent.getCurrentState()).isEqualTo(ChainSyncState.Idle);
    }

    private void performRequestNextExpectingRollForward(int expectedBlockNumber) {
        RequestNext requestNext = new RequestNext();
        agent.receiveResponse(requestNext);
        assertThat(agent.getCurrentState()).isEqualTo(ChainSyncState.CanAwait);
        agent.sendNextMessage();
        assertThat(agent.getCurrentState()).isEqualTo(ChainSyncState.Idle);
        assertThat(agent.getLastSentPoint()).isEqualTo(chainState.pointAt(expectedBlockNumber));
    }

    private void performRequestNextExpectingRollbackward(Point expectedPoint) {
        RequestNext requestNext = new RequestNext();
        // receiveResponse transitions Idle->CanAwait, then processResponse calls handleRollback
        // which internally calls sendNextMessage(), transitioning CanAwait->Idle
        agent.receiveResponse(requestNext);
        assertThat(agent.getCurrentState()).isEqualTo(ChainSyncState.Idle);
    }

    private void performRequestNextExpectingAwaitReply() {
        RequestNext requestNext = new RequestNext();
        agent.receiveResponse(requestNext);
        assertThat(agent.getCurrentState()).isEqualTo(ChainSyncState.CanAwait);
        agent.sendNextMessage();
        assertThat(agent.getCurrentState()).isEqualTo(ChainSyncState.MustReply);
    }

    // ---- Stub Channel that simulates immediate successful writes ----

    static class StubChannel implements Channel {
        private final StubChannelFuture successFuture = new StubChannelFuture();
        private final List<Object> written = new CopyOnWriteArrayList<>();
        private final List<Thread> writerThreads = new CopyOnWriteArrayList<>();
        private volatile ChannelFuture writeFuture = successFuture;
        private volatile EventLoop eventLoop;

        void useEventLoop(EventLoop eventLoop) {
            this.eventLoop = eventLoop;
        }

        /** Leave write futures incomplete, as when the socket is not immediately writable. */
        void holdWriteCompletion() {
            writeFuture = new StubChannelFuture() {
                @Override
                public ChannelFuture addListener(GenericFutureListener<? extends Future<? super Void>> listener) {
                    return this;
                }
            };
        }

        /** ChainSync message ids of the segments written so far. */
        /** Written replies as AW, RB slot, RF block/fork or IF, to check points as well as order. */
        List<String> writtenReplies() {
            return written.stream()
                    .map(segment -> (Array) CborSerializationUtil.deserializeOne(((Segment) segment).getPayload()))
                    .map(StubChannel::describe)
                    .toList();
        }

        private static String describe(Array message) {
            List<DataItem> items = message.getDataItems();
            switch (((UnsignedInteger) items.get(0)).getValue().intValue()) {
                case AWAIT_REPLY:
                    return "AW";
                case ROLL_FORWARD: {
                    byte[] header = ((ByteString) ((Array) items.get(1)).getDataItems().get(1)).getBytes();
                    return "RF " + ((header[0] & 0xFF) | (header[1] & 0xFF) << 8) + "/" + header[2];
                }
                case ROLL_BACKWARD: {
                    List<DataItem> point = ((Array) items.get(1)).getDataItems();
                    return point.isEmpty() ? "RB origin" : "RB " + ((UnsignedInteger) point.get(0)).getValue();
                }
                case INTERSECT_FOUND:
                    return "IF";
                default:
                    return "?" + items.get(0);
            }
        }

        List<Integer> writtenMessageIds() {
            return written.stream()
                    .map(segment -> (Array) CborSerializationUtil.deserializeOne(((Segment) segment).getPayload()))
                    .map(array -> ((UnsignedInteger) array.getDataItems().get(0)).getValue().intValue())
                    .toList();
        }

        @Override public ChannelFuture writeAndFlush(Object msg) {
            written.add(msg);
            writerThreads.add(Thread.currentThread());
            return writeFuture;
        }
        @Override public boolean isActive() { return true; }
        @Override public ChannelFuture writeAndFlush(Object msg, ChannelPromise promise) { return successFuture; }

        // -- Remaining Channel methods (unused, minimal stubs) --
        @Override public ChannelId id() { return null; }
        @Override public EventLoop eventLoop() { return eventLoop; }
        @Override public Channel parent() { return null; }
        @Override public ChannelConfig config() { return null; }
        @Override public boolean isOpen() { return true; }
        @Override public boolean isRegistered() { return true; }
        @Override public ChannelMetadata metadata() { return null; }
        @Override public SocketAddress localAddress() { return null; }
        @Override public SocketAddress remoteAddress() { return null; }
        @Override public ChannelFuture closeFuture() { return null; }
        @Override public boolean isWritable() { return true; }
        @Override public long bytesBeforeUnwritable() { return Long.MAX_VALUE; }
        @Override public long bytesBeforeWritable() { return 0; }
        @Override public Unsafe unsafe() { return null; }
        @Override public ChannelPipeline pipeline() { return null; }
        @Override public io.netty.buffer.ByteBufAllocator alloc() { return null; }
        @Override public <T> Attribute<T> attr(AttributeKey<T> key) { return null; }
        @Override public <T> boolean hasAttr(AttributeKey<T> key) { return false; }
        @Override public ChannelFuture bind(SocketAddress localAddress) { return null; }
        @Override public ChannelFuture connect(SocketAddress remoteAddress) { return null; }
        @Override public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress) { return null; }
        @Override public ChannelFuture disconnect() { return null; }
        @Override public ChannelFuture close() { return null; }
        @Override public ChannelFuture deregister() { return null; }
        @Override public ChannelFuture bind(SocketAddress localAddress, ChannelPromise promise) { return null; }
        @Override public ChannelFuture connect(SocketAddress remoteAddress, ChannelPromise promise) { return null; }
        @Override public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) { return null; }
        @Override public ChannelFuture disconnect(ChannelPromise promise) { return null; }
        @Override public ChannelFuture close(ChannelPromise promise) { return null; }
        @Override public ChannelFuture deregister(ChannelPromise promise) { return null; }
        @Override public Channel read() { return this; }
        @Override public ChannelFuture write(Object msg) { return successFuture; }
        @Override public ChannelFuture write(Object msg, ChannelPromise promise) { return successFuture; }
        @Override public Channel flush() { return this; }
        @Override public ChannelPromise newPromise() { return null; }
        @Override public ChannelProgressivePromise newProgressivePromise() { return null; }
        @Override public ChannelFuture newSucceededFuture() { return successFuture; }
        @Override public ChannelFuture newFailedFuture(Throwable cause) { return null; }
        @Override public ChannelPromise voidPromise() { return null; }
        @Override public int compareTo(Channel o) { return 0; }
    }

    @SuppressWarnings("unchecked")
    static class StubChannelFuture implements ChannelFuture {
        @Override public boolean isSuccess() { return true; }
        @Override public Channel channel() { return null; }
        @Override public ChannelFuture addListener(GenericFutureListener<? extends Future<? super Void>> listener) {
            try {
                ((GenericFutureListener<Future<Void>>) listener).operationComplete(this);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return this;
        }
        @Override public ChannelFuture addListeners(GenericFutureListener<? extends Future<? super Void>>... listeners) { return this; }
        @Override public ChannelFuture removeListener(GenericFutureListener<? extends Future<? super Void>> listener) { return this; }
        @Override public ChannelFuture removeListeners(GenericFutureListener<? extends Future<? super Void>>... listeners) { return this; }
        @Override public ChannelFuture sync() { return this; }
        @Override public ChannelFuture syncUninterruptibly() { return this; }
        @Override public ChannelFuture await() { return this; }
        @Override public ChannelFuture awaitUninterruptibly() { return this; }
        @Override public boolean isVoid() { return false; }
        @Override public boolean isCancellable() { return false; }
        @Override public Throwable cause() { return null; }
        @Override public boolean await(long timeout, TimeUnit unit) { return true; }
        @Override public boolean await(long timeoutMillis) { return true; }
        @Override public boolean awaitUninterruptibly(long timeout, TimeUnit unit) { return true; }
        @Override public boolean awaitUninterruptibly(long timeoutMillis) { return true; }
        @Override public Void getNow() { return null; }
        @Override public boolean cancel(boolean mayInterruptIfRunning) { return false; }
        @Override public boolean isCancelled() { return false; }
        @Override public boolean isDone() { return true; }
        @Override public Void get() { return null; }
        @Override public Void get(long timeout, TimeUnit unit) { return null; }
    }

    // ---- Fake ChainState for testing ----

    static class FakeChainState implements ChainState {
        private final ConcurrentSkipListMap<Long, BlockEntry> blocksBySlot = new ConcurrentSkipListMap<>();
        private final ConcurrentHashMap<String, BlockEntry> blocksByHash = new ConcurrentHashMap<>();
        private volatile ChainTip tip;
        private volatile int fork;

        Point pointAt(int blockNumber) {
            BlockEntry entry = blocksBySlot.get((long) blockNumber);
            if (entry == null) return null;
            return new Point(entry.slot, entry.hashHex);
        }

        void addBlock(int blockNumber) {
            long slot = blockNumber;
            byte[] block = blockBody(blockNumber, fork);
            byte[] hash = BlockSerializer.INSTANCE.deserialize(block).getHeader().getHeaderBody().getBlockHash()
                    .transform(HexUtil::decodeHexString);
            String hashHex = HexUtil.encodeHexString(hash);
            // Create valid CBOR: array [7, h'<blockNumber bytes>'] — era 7 (Conway) + dummy header
            // 0x82 = 2-element array, 0x07 = uint 7, 0x43 = 3-byte bstr, then 3 bytes
            byte[] headerBytes = new byte[]{
                    (byte) 0x82, 0x07, 0x43,
                    (byte) (blockNumber & 0xFF), (byte) ((blockNumber >> 8) & 0xFF), (byte) fork
            };
            BlockEntry entry = new BlockEntry(slot, blockNumber, hash, hashHex, headerBytes, block);
            blocksBySlot.put(slot, entry);
            blocksByHash.put(hashHex, entry);
            tip = new ChainTip(slot, hash, blockNumber);
        }

        /** Roll back to {@code slot}; blocks added afterwards are on a new fork, with different hashes. */
        void switchForkAfter(long slot) {
            rollbackTo(slot);
            fork++;
        }

        @Override
        public void rollbackTo(Long slot) {
            blocksBySlot.tailMap(slot, false).forEach((s, entry) -> blocksByHash.remove(entry.hashHex));
            blocksBySlot.tailMap(slot, false).clear();
            if (!blocksBySlot.isEmpty()) {
                Map.Entry<Long, BlockEntry> last = blocksBySlot.lastEntry();
                BlockEntry e = last.getValue();
                tip = new ChainTip(e.slot, e.hash, e.blockNumber);
            } else {
                tip = null;
            }
        }

        @Override public void storeBlock(byte[] blockHash, Long blockNumber, Long slot, byte[] block) {}
        @Override public byte[] getBlock(byte[] blockHash) {
            BlockEntry e = blocksByHash.get(HexUtil.encodeHexString(blockHash));
            return e != null ? e.block : null;
        }
        @Override public boolean hasBlock(byte[] blockHash) {
            return blocksByHash.containsKey(HexUtil.encodeHexString(blockHash));
        }
        @Override public void storeBlockHeader(byte[] blockHash, Long blockNumber, Long slot, byte[] blockHeader) {}
        @Override public byte[] getBlockHeader(byte[] blockHash) {
            BlockEntry e = blocksByHash.get(HexUtil.encodeHexString(blockHash));
            return e != null ? e.headerBytes : null;
        }
        @Override public byte[] getBlockByNumber(Long blockNumber) {
            BlockEntry e = entryByNumber(blockNumber);
            return e != null ? e.block : null;
        }
        @Override public byte[] getBlockHeaderByNumber(Long blockNumber) {
            BlockEntry e = entryByNumber(blockNumber);
            return e != null ? e.headerBytes : null;
        }

        private BlockEntry entryByNumber(Long blockNumber) {
            for (BlockEntry e : blocksBySlot.values()) {
                if (e.blockNumber == blockNumber) return e;
            }
            return null;
        }
        @Override public ChainTip getTip() { return tip; }
        @Override public ChainTip getHeaderTip() { return tip; }

        @Override public Point findNextBlock(Point currentPoint) {
            if (currentPoint == null) return null;
            if (currentPoint.getSlot() == 0 && currentPoint.getHash() == null) return getFirstBlock();
            Long nextSlot = blocksBySlot.higherKey(currentPoint.getSlot());
            if (nextSlot == null) return null;
            BlockEntry e = blocksBySlot.get(nextSlot);
            return e != null ? new Point(e.slot, e.hashHex) : null;
        }
        @Override public Point findNextBlockHeader(Point currentPoint) { return findNextBlock(currentPoint); }
        @Override public List<Point> findBlocksInRange(Point from, Point to) { return List.of(); }
        @Override public Point findLastPointAfterNBlocks(Point from, long batchSize) { return null; }

        @Override public boolean hasPoint(Point point) {
            if (point == null) return false;
            if (point.getSlot() == 0 && point.getHash() == null) return tip != null;
            if (point.getHash() == null) return false;
            return blocksByHash.containsKey(point.getHash());
        }
        @Override public Point getFirstBlock() {
            if (blocksBySlot.isEmpty()) return null;
            BlockEntry e = blocksBySlot.firstEntry().getValue();
            return new Point(e.slot, e.hashHex);
        }
        @Override public Long getBlockNumberBySlot(Long slot) {
            BlockEntry e = blocksBySlot.get(slot);
            return e != null ? (long) e.blockNumber : null;
        }
        @Override public Long getSlotByBlockNumber(Long blockNumber) {
            for (BlockEntry e : blocksBySlot.values()) {
                if (e.blockNumber == blockNumber) return e.slot;
            }
            return null;
        }

        /**
         * A real preprod block with its block number, slot and previous hash replaced, so every block and fork
         * has its own hash. The server's rollback search parses stored blocks to recover their hashes.
         */
        private static byte[] blockBody(int blockNumber, int fork) {
            Array block = (Array) CborSerializationUtil.deserializeOne(BLOCK_TEMPLATE);
            Array header = (Array) ((Array) block.getDataItems().get(1)).getDataItems().get(0);
            List<DataItem> headerBody = ((Array) header.getDataItems().get(0)).getDataItems();
            headerBody.set(0, new UnsignedInteger(blockNumber));
            headerBody.set(1, new UnsignedInteger(blockNumber));
            byte[] prevHash = new byte[32];
            prevHash[0] = (byte) fork;
            headerBody.set(2, new ByteString(prevHash));
            return CborSerializationUtil.serialize(block);
        }

        private static final byte[] BLOCK_TEMPLATE = loadBlockTemplate();

        private static byte[] loadBlockTemplate() {
            try (var in = FakeChainState.class.getResourceAsStream("/block/preprod286853.txt")) {
                return HexUtil.decodeHexString(new String(in.readAllBytes()).trim());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        record BlockEntry(long slot, int blockNumber, byte[] hash, String hashHex, byte[] headerBytes,
                          byte[] block) {}
    }
}
