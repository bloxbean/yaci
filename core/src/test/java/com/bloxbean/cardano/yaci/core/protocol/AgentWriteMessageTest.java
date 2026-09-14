package com.bloxbean.cardano.yaci.core.protocol;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentWriteMessageTest {

    @Test
    void writeMessageFailureCallbackIsInvokedWhenChannelIsInactive() {
        TestAgent agent = new TestAgent();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        agent.write(new TestMessage(new byte[]{1}), () -> {
        }, failure::set);

        assertNotNull(failure.get());
        assertTrue(failure.get().getMessage().contains("channel is null or inactive"));
    }

    @Test
    void writeMessageFailureCallbackIsInvokedWhenNettyWriteFails() {
        RuntimeException writeFailure = new RuntimeException("write failed");
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                promise.setFailure(writeFailure);
            }
        });
        TestAgent agent = new TestAgent();
        agent.setChannel(channel);
        AtomicBoolean success = new AtomicBoolean(false);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        agent.write(new TestMessage(new byte[]{1}), () -> success.set(true), failure::set);

        assertFalse(success.get());
        assertSame(writeFailure, failure.get());
    }

    @Test
    void writeMessageRethrowsWhenSerializationFails() {
        RuntimeException serializeFailure = new RuntimeException("serialize failed");
        EmbeddedChannel channel = new EmbeddedChannel();
        TestAgent agent = new TestAgent();
        agent.setChannel(channel);
        AtomicBoolean success = new AtomicBoolean(false);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> agent.write(new FailingMessage(serializeFailure), () -> success.set(true), failure::set));

        assertSame(serializeFailure, exception);
        assertFalse(success.get());
        assertNull(failure.get());
        assertTrue(channel.isActive());
    }

    @Test
    void writeMessageRethrowsWhenSerializationThrowsError() {
        AssertionError serializeFailure = new AssertionError("serialize overflow");
        EmbeddedChannel channel = new EmbeddedChannel();
        TestAgent agent = new TestAgent();
        agent.setChannel(channel);
        AtomicBoolean success = new AtomicBoolean(false);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        AssertionError exception = assertThrows(AssertionError.class,
                () -> agent.write(new FailingErrorMessage(serializeFailure), () -> success.set(true), failure::set));

        assertSame(serializeFailure, exception);
        assertFalse(success.get());
        assertNull(failure.get());
        assertTrue(channel.isActive());
    }

    @Test
    void writeMessageFailureCallbackIsInvokedWhenNettyWriteThrowsSynchronously() {
        RuntimeException writeFailure = new RuntimeException("write threw");
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                throw writeFailure;
            }
        });
        TestAgent agent = new TestAgent();
        agent.setChannel(channel);
        AtomicBoolean success = new AtomicBoolean(false);
        AtomicInteger failures = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        agent.write(new TestMessage(new byte[]{1}), () -> success.set(true), throwable -> {
            failures.incrementAndGet();
            failure.set(throwable);
        });

        assertFalse(success.get());
        assertEquals(1, failures.get());
        assertNotNull(failure.get());
    }

    @Test
    void segmentedWriteFailureInvokesFailureCallbackExactlyOnce() {
        RuntimeException writeFailure = new RuntimeException("segment failed");
        AtomicInteger writes = new AtomicInteger();
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                if (writes.incrementAndGet() == 2)
                    promise.setFailure(writeFailure);
                else
                    promise.setSuccess();
            }
        });
        TestAgent agent = new TestAgent();
        agent.setChannel(channel);
        AtomicBoolean success = new AtomicBoolean(false);
        AtomicInteger failures = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        agent.write(new TestMessage(new byte[131071]), () -> success.set(true), throwable -> {
            failures.incrementAndGet();
            failure.set(throwable);
        });

        assertFalse(success.get());
        assertEquals(1, failures.get());
        assertSame(writeFailure, failure.get());
        assertFalse(channel.isActive());
    }

    @Test
    void legacyWriteMessageClosesChannelOnWriteFailure() {
        RuntimeException writeFailure = new RuntimeException("write failed");
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                promise.setFailure(writeFailure);
            }
        });
        TestAgent agent = new TestAgent();
        agent.setChannel(channel);
        AtomicBoolean success = new AtomicBoolean(false);

        agent.writeLegacy(new TestMessage(new byte[]{1}), () -> success.set(true));
        channel.runPendingTasks();

        assertFalse(success.get());
        assertFalse(channel.isActive());
    }

    @Test
    void legacyWriteMessageClosesOriginalFailedChannelAfterReconnect() {
        RuntimeException writeFailure = new RuntimeException("late write failed");
        AtomicReference<ChannelPromise> pendingWrite = new AtomicReference<>();
        EmbeddedChannel failedChannel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                pendingWrite.set(promise);
            }
        });
        EmbeddedChannel reconnectedChannel = new EmbeddedChannel();
        TestAgent agent = new TestAgent();
        agent.setChannel(failedChannel);

        agent.writeLegacy(new TestMessage(new byte[]{1}), () -> {
        });
        assertNotNull(pendingWrite.get());

        agent.setChannel(reconnectedChannel);
        pendingWrite.get().setFailure(writeFailure);
        failedChannel.runPendingTasks();
        reconnectedChannel.runPendingTasks();

        assertFalse(failedChannel.isActive());
        assertTrue(reconnectedChannel.isActive());
    }

    @Test
    void legacyWriteMessageRethrowsSerializationFailureWithoutClosingChannel() {
        RuntimeException serializeFailure = new RuntimeException("serialize failed");
        EmbeddedChannel channel = new EmbeddedChannel();
        TestAgent agent = new TestAgent();
        agent.setChannel(channel);

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> agent.writeLegacy(new FailingMessage(serializeFailure), () -> {
                }));

        assertSame(serializeFailure, exception);
        assertTrue(channel.isActive());
    }

    @Test
    void throwingFailureCallbackDoesNotEscapeNettyWriteFailurePath() {
        RuntimeException writeFailure = new RuntimeException("write failed");
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                promise.setFailure(writeFailure);
            }
        });
        TestAgent agent = new TestAgent();
        agent.setChannel(channel);

        agent.write(new TestMessage(new byte[]{1}), () -> {
        }, throwable -> {
            throw new IllegalStateException("callback failed");
        });

        assertTrue(channel.isActive());
    }

    private static class TestAgent extends Agent<AgentListener> {
        private TestAgent() {
            super(true);
            this.currentState = TestState.INSTANCE;
        }

        @Override
        public int getProtocolId() {
            return 42;
        }

        @Override
        public Message buildNextMessage() {
            return null;
        }

        @Override
        public void processResponse(Message message) {
        }

        @Override
        public boolean isDone() {
            return false;
        }

        private void write(Message message, Runnable onSuccess, Consumer<Throwable> onFailure) {
            writeMessage(message, onSuccess, onFailure);
        }

        private void writeLegacy(Message message, Runnable onSuccess) {
            writeMessage(message, onSuccess);
        }
    }

    private enum TestState implements State {
        INSTANCE;

        @Override
        public State nextState(Message message) {
            return this;
        }

        @Override
        public boolean hasAgency(boolean isClient) {
            return true;
        }
    }

    private record TestMessage(byte[] payload) implements Message {
        @Override
        public byte[] serialize() {
            return payload;
        }
    }

    private record FailingMessage(RuntimeException failure) implements Message {
        @Override
        public byte[] serialize() {
            throw failure;
        }
    }

    private record FailingErrorMessage(Error failure) implements Message {
        @Override
        public byte[] serialize() {
            throw failure;
        }
    }
}
