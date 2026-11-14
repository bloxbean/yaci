package com.bloxbean.cardano.yaci.core.network;

import com.bloxbean.cardano.yaci.core.network.handlers.MiniProtoClientInboundHandler;
import com.bloxbean.cardano.yaci.core.network.handlers.MiniProtoRequestDataEncoder;
import com.bloxbean.cardano.yaci.core.network.handlers.MiniProtoStreamingByteToMessageDecoder;
import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.AgentListener;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.State;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.N2NVersionData;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.Reason;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.ReasonVersionMismatch;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.Refuse;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AppOnlyClientHandshakeTest {
    private static final long MAGIC = 424242;
    private static final byte[] EMPTY_CBOR_ARRAY = {(byte) 0x80};

    @Test
    void appTrafficBeforeNegotiationClosesWithoutDispatch() {
        for (int protocol : List.of(100, 103)) {
            HandshakeAgent handshake = appHandshake();
            CountingAgent agent = new CountingAgent(protocol);
            EmbeddedChannel channel = channel(handshake, agent);
            try {
                channel.writeInbound(frame(protocol, EMPTY_CBOR_ARRAY));
                assertFalse(channel.isActive());
                assertEquals(0, agent.messages);
                assertFalse(handshake.isHandshakeSuccessful());
                assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
                        assertThrows(IllegalStateException.class, session(handshake)::handshake));
            } finally {
                channel.finishAndReleaseAll();
            }
        }
    }

    @Test
    void invalidAcceptOrRefusalCannotDeliverCoalescedCatchUpTraffic() {
        for (Message reply : List.of(accepted(100, MAGIC), accepted(101, MAGIC + 1),
                new Refuse(new ReasonVersionMismatch(List.of(14L))))) {
            HandshakeAgent handshake = appHandshake();
            CountingAgent agent = new CountingAgent(103);
            EmbeddedChannel channel = channel(handshake, agent);
            AtomicInteger accepted = new AtomicInteger();
            AtomicInteger rejected = new AtomicInteger();
            handshake.addListener(new HandshakeAgentListener() {
                @Override public void handshakeOk() { accepted.incrementAndGet(); }
                @Override public void handshakeError(Reason reason) { rejected.incrementAndGet(); }
            });
            try {
                handshake.sendRequest(handshake.buildNextMessage());
                channel.writeInbound(Unpooled.wrappedBuffer(frame(0, reply.serialize()),
                        frame(103, EMPTY_CBOR_ARRAY)));
                assertFalse(channel.isActive());
                assertEquals(0, agent.messages);
                assertEquals(0, accepted.get());
                assertEquals(1, rejected.get());
                assertTrue(handshake.isDone());
                assertFalse(handshake.isHandshakeSuccessful());
                assertNull(handshake.getProtocolVersion());
                assertThrows(IllegalStateException.class, session(handshake)::handshake);
            } finally {
                channel.finishAndReleaseAll();
            }
        }
    }

    @Test
    void validatedAcceptanceAllowsCoalescedAppTrafficAndSessionCompletion() {
        HandshakeAgent handshake = appHandshake();
        CountingAgent agent = new CountingAgent(103);
        EmbeddedChannel channel = channel(handshake, agent);
        try {
            handshake.sendRequest(handshake.buildNextMessage());
            channel.writeInbound(Unpooled.wrappedBuffer(frame(0, accepted(101, MAGIC).serialize()),
                    frame(103, EMPTY_CBOR_ARRAY)));
            assertTrue(channel.isActive());
            assertTrue(handshake.isHandshakeSuccessful());
            assertEquals(1, agent.messages);
            assertDoesNotThrow(session(handshake)::handshake);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void resetClearsReadinessAndNegotiatedVersionBeforeReusedAgentCanDispatch() {
        HandshakeAgent handshake = appHandshake();
        CountingAgent agent = new CountingAgent(103);
        EmbeddedChannel channel = channel(handshake, agent);
        try {
            handshake.sendRequest(handshake.buildNextMessage());
            channel.writeInbound(frame(0, accepted(101, MAGIC).serialize()));
            assertTrue(handshake.isHandshakeSuccessful());
            handshake.reset();
            assertFalse(handshake.isDone());
            assertFalse(handshake.isHandshakeSuccessful());
            assertNull(handshake.getProtocolVersion());
            channel.writeInbound(frame(103, EMPTY_CBOR_ARRAY));
            assertFalse(channel.isActive());
            assertEquals(0, agent.messages);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void refusalClosesEvenWhenFailureCallbackThrows() {
        HandshakeAgent handshake = appHandshake();
        CountingAgent agent = new CountingAgent(103);
        EmbeddedChannel channel = channel(handshake, agent);
        handshake.addListener(new HandshakeAgentListener() {
            @Override public void handshakeError(Reason reason) {
                throw new IllegalStateException("listener failure");
            }
        });
        try {
            handshake.sendRequest(handshake.buildNextMessage());
            assertThrows(IllegalStateException.class, () -> channel.writeInbound(
                    frame(0, accepted(101, MAGIC + 1).serialize())));
            assertFalse(channel.isActive());
            assertTrue(handshake.isDone());
            assertFalse(handshake.isHandshakeSuccessful());
            assertThrows(IllegalStateException.class, session(handshake)::handshake);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void unsupportedQueryReplyAndMalformedAcceptanceFailClosed() {
        for (byte[] response : List.of(new byte[]{(byte) 0x82, 0x03, (byte) 0xa0},
                new byte[]{(byte) 0x81, 0x01})) {
            HandshakeAgent handshake = appHandshake();
            CountingAgent agent = new CountingAgent(103);
            EmbeddedChannel channel = channel(handshake, agent);
            AtomicInteger rejected = new AtomicInteger();
            handshake.addListener(new HandshakeAgentListener() {
                @Override public void handshakeError(Reason reason) { rejected.incrementAndGet(); }
            });
            try {
                handshake.sendRequest(handshake.buildNextMessage());
                channel.writeInbound(Unpooled.wrappedBuffer(frame(0, response), frame(103, EMPTY_CBOR_ARRAY)));
                assertFalse(channel.isActive());
                assertEquals(0, agent.messages);
                assertEquals(1, rejected.get());
                assertTrue(handshake.isDone());
                assertFalse(handshake.isHandshakeSuccessful());
                assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
                        assertThrows(IllegalStateException.class, session(handshake)::handshake));
            } finally {
                channel.finishAndReleaseAll();
            }
        }
    }

    @Test
    void legacyCardanoDispatchBehaviorIsUnchanged() {
        HandshakeAgent handshake = new HandshakeAgent(N2NVersionTableConstant.v11AndAbove(MAGIC));
        CountingAgent agent = new CountingAgent(103);
        EmbeddedChannel channel = channel(handshake, agent);
        try {
            channel.writeInbound(frame(103, EMPTY_CBOR_ARRAY));
            assertTrue(channel.isActive());
            assertEquals(1, agent.messages);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private HandshakeAgent appHandshake() {
        return new HandshakeAgent(N2NVersionTableConstant.appOnly(MAGIC, true));
    }

    private EmbeddedChannel channel(HandshakeAgent handshake, CountingAgent agent) {
        EmbeddedChannel channel = new EmbeddedChannel(new MiniProtoRequestDataEncoder(),
                new MiniProtoStreamingByteToMessageDecoder(agent),
                new MiniProtoClientInboundHandler(handshake, new Agent[]{agent}));
        handshake.setChannel(channel);
        agent.setChannel(channel);
        return channel;
    }

    private Session session(HandshakeAgent handshake) {
        return new Session(new InetSocketAddress("127.0.0.1", 1), new Bootstrap(),
                NodeClientConfig.defaultConfig(), handshake, new Agent[0]);
    }

    private AcceptVersion accepted(long version, long magic) {
        return new AcceptVersion(version, new N2NVersionData(magic, false, 0, false));
    }

    private static ByteBuf frame(int protocol, byte[] payload) {
        return Unpooled.buffer(8 + payload.length).writeInt(0).writeShort(protocol | 0x8000)
                .writeShort(payload.length).writeBytes(payload);
    }

    private static class CountingAgent extends Agent<AgentListener> {
        private final int protocol;
        private int messages;

        CountingAgent(int protocol) {
            super(true);
            this.protocol = protocol;
            currentState = new State() {
                @Override public State nextState(Message message) { return this; }
                @Override public boolean hasAgency(boolean isClient) { return false; }
            };
        }

        @Override public int getProtocolId() { return protocol; }
        @Override public Message buildNextMessage() { return null; }
        @Override public void processResponse(Message message) { messages++; }
        @Override public boolean isDone() { return false; }
    }
}
