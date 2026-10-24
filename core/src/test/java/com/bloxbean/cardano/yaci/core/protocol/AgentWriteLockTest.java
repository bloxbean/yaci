package com.bloxbean.cardano.yaci.core.protocol;

import com.bloxbean.cardano.yaci.core.protocol.keepalive.KeepAliveAgent;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalServerChannel;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AgentWriteLockTest {

    private static final int SEGMENT = 65535;

    /**
     * A write that completes on the calling thread runs its success callback there. The callback takes the agent
     * lock, so it must not run while the channel lock is held: agent-locked writers take the locks in the opposite
     * order, and the two threads deadlock.
     */
    @Test
    void successCallbackRunsWithoutChannelLock() {
        EmbeddedChannel channel = new EmbeddedChannel();
        AtomicReference<Boolean> channelLockHeld = new AtomicReference<>();
        KeepAliveAgent agent = new KeepAliveAgent() {
            @Override
            public synchronized void sendRequest(Message message) {
                channelLockHeld.set(Thread.holdsLock(channel));
                super.sendRequest(message);
            }
        };
        agent.setChannel((Channel) channel);

        agent.sendNextMessage();

        assertThat(channel.<Object>readOutbound()).isInstanceOf(Segment.class);
        assertThat(channelLockHeld.get()).isFalse();
        channel.finishAndReleaseAll();
    }

    // A write the socket cannot take yet stays pending; a later write's flush completes it and runs its callback
    // on the thread of that later write.

    @Test
    void pendingWriteCompletedBySingleSegmentWrite() {
        assertPendingWriteCallbackRunsWithoutChannelLock(10, 10);
    }

    @Test
    void pendingWriteCompletedBySegmentedWrite() {
        assertPendingWriteCallbackRunsWithoutChannelLock(10, 2 * SEGMENT);
    }

    @Test
    void pendingSegmentedWriteCompletedBySingleSegmentWrite() {
        assertPendingWriteCallbackRunsWithoutChannelLock(2 * SEGMENT, 10);
    }

    private static void assertPendingWriteCallbackRunsWithoutChannelLock(int firstSize, int secondSize) {
        DelayedWriteChannel channel = new DelayedWriteChannel();
        TestAgent first = new TestAgent(channel);
        TestAgent second = new TestAgent(channel);
        List<Boolean> channelLockHeld = new ArrayList<>();

        first.write(payload(firstSize), () -> channelLockHeld.add(Thread.holdsLock(channel)));
        assertThat(channelLockHeld).as("first write still pending").isEmpty();

        channel.drain = true;
        second.write(payload(secondSize), null);

        assertThat(channelLockHeld).containsExactly(false);
        channel.finishAndReleaseAll();
    }

    @Test
    void largeMessageIsWrittenAsSegmentsInOrderAndCallsBackOnce() {
        EmbeddedChannel channel = new EmbeddedChannel();
        TestAgent agent = new TestAgent(channel);
        AtomicInteger callbacks = new AtomicInteger();
        byte[] payload = payload(2 * SEGMENT + 10);

        agent.write(payload, callbacks::incrementAndGet);

        ByteArrayOutputStream received = new ByteArrayOutputStream();
        List<Integer> sizes = new ArrayList<>();
        Segment segment;
        while ((segment = channel.readOutbound()) != null) {
            assertThat(segment.getProtocol()).isEqualTo(TestAgent.PROTOCOL_ID);
            sizes.add(segment.getPayload().length);
            received.writeBytes(segment.getPayload());
        }
        assertThat(sizes).containsExactly(SEGMENT, SEGMENT, 10);
        assertThat(received.toByteArray()).isEqualTo(payload);
        assertThat(callbacks).hasValue(1);
        channel.finishAndReleaseAll();
    }

    /**
     * The deadlock on a real event loop: agent A has a write pending, and a thread holding A's lock writes on the
     * same channel while the event loop's next write completes A's pending write, whose callback needs A's lock.
     * Neither side may wait for the other.
     */
    @Test
    void writerHoldingAgentLockDoesNotDeadlockWithCallbackOnEventLoop() throws Exception {
        EventLoopGroup group = new DefaultEventLoopGroup(1);
        try {
            HoldingHandler holding = new HoldingHandler();
            Channel channel = connect(group, holding);
            TestAgent a = new TestAgent(channel);
            TestAgent b = new TestAgent(channel);
            CountDownLatch callbackRan = new CountDownLatch(1);

            a.write(payload(10), () -> {
                synchronized (a) {
                    callbackRan.countDown();
                }
            });
            assertThat(holding.held.await(5, TimeUnit.SECONDS)).as("A's write pending").isTrue();

            Thread writer = new Thread(() -> {
                synchronized (a) {
                    holding.release = true;
                    channel.eventLoop().execute(() -> b.write(payload(10), null));
                    try {
                        holding.flushing.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    a.write(payload(10), null);
                }
            });
            writer.setDaemon(true);
            writer.start();

            assertThat(callbackRan.await(5, TimeUnit.SECONDS)).as("A's callback ran").isTrue();
            writer.join(5000);
            assertThat(writer.isAlive()).isFalse();
            channel.close().sync();
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }

    /**
     * Writes queued from another thread count against the channel's writability before the event loop runs them, so
     * a producer that checks {@code isWritable()} (BlockFetchServerAgent) slows down when the event loop falls behind.
     */
    @Test
    void writesQueuedOffTheEventLoopCountAgainstWritability() throws Exception {
        EventLoopGroup group = new DefaultEventLoopGroup(1);
        CountDownLatch resume = new CountDownLatch(1);
        try {
            Channel channel = connect(group, new ChannelOutboundHandlerAdapter());
            TestAgent agent = new TestAgent(channel);
            CountDownLatch paused = new CountDownLatch(1);
            channel.eventLoop().execute(() -> {
                paused.countDown();
                try {
                    resume.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(paused.await(5, TimeUnit.SECONDS)).as("event loop paused").isTrue();

            for (int i = 0; i < 2000; i++)
                agent.write(payload(10), null);

            assertThat(channel.isWritable()).isFalse();
        } finally {
            resume.countDown();
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }

    private static Channel connect(EventLoopGroup group, ChannelHandler handler) throws InterruptedException {
        LocalAddress address = new LocalAddress("agent-write-" + UUID.randomUUID());
        new ServerBootstrap().group(group).channel(LocalServerChannel.class)
                .childHandler(new ChannelInboundHandlerAdapter()).bind(address).sync();
        return new Bootstrap().group(group).channel(LocalChannel.class).handler(handler).connect(address).sync()
                .channel();
    }

    private static byte[] payload(int size) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++)
            bytes[i] = (byte) i;
        return bytes;
    }

    private static final class TestAgent extends Agent<AgentListener> {
        static final int PROTOCOL_ID = 8;

        TestAgent(Channel channel) {
            super(true);
            setChannel(channel);
        }

        void write(byte[] payload, Runnable onSuccess) {
            writeMessage(new Message<Void>() {
                @Override
                public byte[] serialize() {
                    return payload;
                }
            }, onSuccess);
        }

        @Override
        public int getProtocolId() {
            return PROTOCOL_ID;
        }

        @Override
        public boolean isDone() {
            return false;
        }

        @Override
        protected Message buildNextMessage() {
            return null;
        }

        @Override
        protected void processResponse(Message message) {
        }
    }

    /** Keeps flushed writes pending until {@code drain} is set, as a socket that cannot take them yet does. */
    private static final class DelayedWriteChannel extends EmbeddedChannel {
        boolean drain;

        @Override
        protected void doWrite(ChannelOutboundBuffer in) throws Exception {
            if (drain)
                super.doWrite(in);
        }
    }

    /** Holds writes until {@code release} is set; the next flush then passes them on and completes them. */
    private static final class HoldingHandler extends ChannelOutboundHandlerAdapter {
        final CountDownLatch held = new CountDownLatch(1);
        final CountDownLatch flushing = new CountDownLatch(1);
        final List<Object[]> pending = new ArrayList<>();
        volatile boolean release;

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            pending.add(new Object[]{msg, promise});
            held.countDown();
        }

        @Override
        public void flush(ChannelHandlerContext ctx) {
            if (!release)
                return;
            flushing.countDown();
            for (Object[] write : pending)
                ctx.write(write[0], (ChannelPromise) write[1]);
            pending.clear();
            ctx.flush();
        }
    }
}
