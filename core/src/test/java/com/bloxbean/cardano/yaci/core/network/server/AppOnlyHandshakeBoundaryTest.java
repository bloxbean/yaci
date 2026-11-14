package com.bloxbean.cardano.yaci.core.network.server;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.AgentListener;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.State;
import com.bloxbean.cardano.yaci.core.protocol.Segment;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshkeState;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.ReasonVersionMismatch;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.N2NVersionData;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.ProposedVersions;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AppOnlyHandshakeBoundaryTest {
    private static final long MAGIC = 424242;
    private static final byte[] EMPTY_CBOR_ARRAY = {(byte) 0x80};

    @Test
    void appMessagesBeforeHandshakeCloseWithoutDispatching() {
        for (int protocol : List.of(100, 103)) {
            EmbeddedChannel channel = new EmbeddedChannel();
            CountingAgent agent = new CountingAgent(protocol);
            try {
                NodeServerSession session = session(channel, MAGIC, agent, null);
                channel.pipeline().fireChannelWritabilityChanged();
                assertEquals(0, agent.writableCalls);
                assertFalse(session.isReadyForProtocolMessages());
                channel.writeInbound(frame(protocol, EMPTY_CBOR_ARRAY));
                assertFalse(channel.isActive());
                assertEquals(0, agent.messages);
            } finally {
                channel.finishAndReleaseAll();
            }
        }
    }

    @Test
    void wrongNetworkAndCardanoProposalsCloseEvenWithoutConnectionListener() {
        for (boolean withListener : List.of(false, true)) {
            for (boolean wrongNetwork : List.of(false, true)) {
                EmbeddedChannel channel = new EmbeddedChannel();
                CountingAgent agent = new CountingAgent(100);
                AtomicInteger failures = new AtomicInteger();
                ServerConnectionListener listener = withListener ? new ServerConnectionListener() {
                    @Override
                    public void onHandshakeFailed(Channel channel, Throwable error) {
                        failures.incrementAndGet();
                    }
                } : null;
                try {
                    NodeServerSession session = session(channel, MAGIC, agent, listener);
                    ProposedVersions proposal = new ProposedVersions(wrongNetwork
                            ? N2NVersionTableConstant.appOnly(MAGIC + 1, true)
                            : N2NVersionTableConstant.v11AndAboveWithAppLayer(MAGIC));
                    // A peer cannot smuggle app traffic behind a refused proposal in the same read.
                    channel.writeInbound(Unpooled.wrappedBuffer(frame(0, proposal.serialize()),
                            frame(100, EMPTY_CBOR_ARRAY)));
                    assertFalse(channel.isActive());
                    assertFalse(session.isReadyForProtocolMessages());
                    assertEquals(0, agent.messages);
                    assertEquals(withListener ? 1 : 0, failures.get());
                    ByteBuf refusal = channel.readOutbound();
                    assertNotNull(refusal, "Refusal must be written before closing");
                    try {
                        assertInstanceOf(ReasonVersionMismatch.class, HandshkeState.Confirm.handleInbound(
                                Segment.deserialize(refusal).getPayload()));
                    } finally {
                        refusal.release();
                    }
                } finally {
                    channel.finishAndReleaseAll();
                }
            }
        }
    }

    @Test
    void successfulWireHandshakePermitsAppTrafficIncludingUnsignedMagic() {
        for (long magic : new long[]{MAGIC, 0x8000_0000L, 0xffff_ffffL}) {
            EmbeddedChannel channel = new EmbeddedChannel();
            CountingAgent agent = new CountingAgent(103);
            try {
                NodeServerSession session = session(channel, magic, agent, null);
                channel.writeInbound(frame(0, new ProposedVersions(
                        N2NVersionTableConstant.appOnly(magic, true)).serialize()));
                assertTrue(channel.isActive());
                assertTrue(session.isReadyForProtocolMessages());
                assertEquals(magic, session.getHandshakeAgent().getProtocolVersion().getVersionData().getNetworkMagic());
                channel.writeInbound(frame(103, EMPTY_CBOR_ARRAY));
                assertEquals(1, agent.messages);
                channel.pipeline().fireChannelWritabilityChanged();
                assertEquals(1, agent.writableCalls);
            } finally {
                channel.finishAndReleaseAll();
            }
        }
    }

    @Test
    void forgedAcceptanceCannotOpenServerTrafficGate() {
        EmbeddedChannel channel = new EmbeddedChannel();
        CountingAgent agent = new CountingAgent(100);
        try {
            NodeServerSession session = session(channel, MAGIC, agent, null);
            channel.writeInbound(frame(0, new AcceptVersion(101,
                    new N2NVersionData(MAGIC, true, 0, false)).serialize()));
            assertFalse(channel.isActive());
            assertFalse(session.isReadyForProtocolMessages());
            assertNull(session.getHandshakeAgent().getProtocolVersion());
            assertEquals(0, agent.messages);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void legacyServerRetainsMutableFactoryListAndToleratesNullFactories() throws Exception {
        List<AgentFactory> factories = new ArrayList<>();
        factories.add(null);
        NodeServer server = new NodeServer(0, N2NVersionTableConstant.v11AndAbove(MAGIC),
                null, null, null, factories);
        EmbeddedChannel channel = new EmbeddedChannel();
        CountingAgent addedLater = new CountingAgent(100);
        try {
            factories.add(() -> addedLater);
            var field = NodeServer.class.getDeclaredField("agentFactories");
            field.setAccessible(true);
            List<AgentFactory> retained = (List<AgentFactory>) field.get(server);
            assertSame(factories, retained);
            NodeServerSession session = new NodeServerSession(channel,
                    N2NVersionTableConstant.v11AndAbove(MAGIC), null, null, null, retained);
            assertTrue(List.of(session.getAgents()).contains(addedLater));
            assertTrue(session.isReadyForProtocolMessages(), "Legacy Cardano dispatch remains unchanged");
        } finally {
            channel.finishAndReleaseAll();
            server.shutdown();
        }
    }

    private NodeServerSession session(EmbeddedChannel channel, long magic, CountingAgent agent,
                                      ServerConnectionListener listener) {
        NodeServerSession session = new NodeServerSession(channel,
                N2NVersionTableConstant.appOnly(magic, false), null, null, null,
                List.of(() -> agent), listener, true);
        channel.pipeline().fireChannelActive();
        return session;
    }

    private static ByteBuf frame(int protocol, byte[] payload) {
        return Unpooled.buffer(8 + payload.length).writeInt(0).writeShort(protocol)
                .writeShort(payload.length).writeBytes(payload);
    }

    private static class CountingAgent extends Agent<AgentListener> {
        private final int protocol;
        private int messages;
        private int writableCalls;

        CountingAgent(int protocol) {
            super(false);
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
        @Override public void onChannelWritabilityChanged(Channel channel) { writableCalls++; }
    }
}
