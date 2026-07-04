package com.bloxbean.cardano.yaci.core.network.handlers;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.AgentListener;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.Segment;
import com.bloxbean.cardano.yaci.core.protocol.State;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiniProtoStreamingByteToMessageDecoderTest {

    @Test
    void preservesEmptyIndefiniteMapBreakByte() {
        byte[] payload = bytes(0xbf, 0xff);
        EmbeddedChannel channel = channelForProtocol(19);

        assertTrue(channel.writeInbound(muxSegment(19, payload)));

        Segment segment = channel.readInbound();
        assertArrayEquals(payload, segment.getPayload());
        assertNull(channel.readInbound());
    }

    @Test
    void preservesNonCanonicalCborBytes() {
        byte[] payload = bytes(0x18, 0x00);
        EmbeddedChannel channel = channelForProtocol(42);

        assertTrue(channel.writeInbound(muxSegment(42, payload)));

        Segment segment = channel.readInbound();
        assertArrayEquals(payload, segment.getPayload());
        assertNull(channel.readInbound());
    }

    @Test
    void splitsMultipleArrayMessagesFromOneMuxSegment() {
        EmbeddedChannel channel = channelForProtocol(7);

        assertTrue(channel.writeInbound(muxSegment(7, bytes(0x81, 0x01, 0x82, 0x02, 0x03))));

        Segment first = channel.readInbound();
        Segment second = channel.readInbound();
        assertArrayEquals(bytes(0x81, 0x01), first.getPayload());
        assertArrayEquals(bytes(0x82, 0x02, 0x03), second.getPayload());
        assertNull(channel.readInbound());
    }

    @Test
    void groupsArrayWithFollowingNonArrayItemsUntilNextArray() {
        EmbeddedChannel channel = channelForProtocol(7);

        assertTrue(channel.writeInbound(muxSegment(7, bytes(0x81, 0x01, 0x02, 0x41, 0xaa, 0x81, 0x03))));

        Segment first = channel.readInbound();
        Segment second = channel.readInbound();
        assertArrayEquals(bytes(0x81, 0x01, 0x02, 0x41, 0xaa), first.getPayload());
        assertArrayEquals(bytes(0x81, 0x03), second.getPayload());
        assertNull(channel.readInbound());
    }

    @Test
    void waitsForGroupedTrailingNonArrayItemAcrossMuxSegments() {
        EmbeddedChannel channel = channelForProtocol(7);

        assertFalse(channel.writeInbound(muxSegment(7, bytes(0x81, 0x01, 0x42))));
        assertTrue(channel.writeInbound(muxSegment(7, bytes(0xaa, 0xbb))));

        Segment segment = channel.readInbound();
        assertArrayEquals(bytes(0x81, 0x01, 0x42, 0xaa, 0xbb), segment.getPayload());
        assertNull(channel.readInbound());
    }

    @Test
    void waitsForSingleMessageSplitAcrossMuxSegments() {
        EmbeddedChannel channel = channelForProtocol(7);

        assertFalse(channel.writeInbound(muxSegment(7, bytes(0x82, 0x01))));
        assertTrue(channel.writeInbound(muxSegment(7, bytes(0x02))));

        Segment segment = channel.readInbound();
        assertArrayEquals(bytes(0x82, 0x01, 0x02), segment.getPayload());
        assertNull(channel.readInbound());
    }

    @Test
    void splitsTaggedArrayMessagesFromOneMuxSegment() {
        EmbeddedChannel channel = channelForProtocol(7);

        assertTrue(channel.writeInbound(muxSegment(7, bytes(0xc0, 0x81, 0x01, 0xc0, 0x81, 0x02))));

        Segment first = channel.readInbound();
        Segment second = channel.readInbound();
        assertArrayEquals(bytes(0xc0, 0x81, 0x01), first.getPayload());
        assertArrayEquals(bytes(0xc0, 0x81, 0x02), second.getPayload());
        assertNull(channel.readInbound());
    }

    @Test
    void waitsForTaggedArrayMessageStartSplitAcrossMuxSegments() {
        EmbeddedChannel channel = channelForProtocol(7);

        assertFalse(channel.writeInbound(muxSegment(7, bytes(0x81, 0x01, 0xc0))));
        assertTrue(channel.writeInbound(muxSegment(7, bytes(0x81, 0x02))));

        Segment first = channel.readInbound();
        Segment second = channel.readInbound();
        assertArrayEquals(bytes(0x81, 0x01), first.getPayload());
        assertArrayEquals(bytes(0xc0, 0x81, 0x02), second.getPayload());
        assertNull(channel.readInbound());
    }

    @Test
    void closesChannelOnMalformedCborPayload() {
        EmbeddedChannel channel = channelForProtocol(7);

        assertFalse(channel.writeInbound(muxSegment(7, bytes(0xff))));
        channel.runPendingTasks();

        assertFalse(channel.isActive());
        assertNull(channel.readInbound());
    }

    @Test
    void discardsRemainingMuxSegmentsAfterMalformedCborPayload() {
        EmbeddedChannel channel = channelForProtocol(7);

        assertFalse(channel.writeInbound(muxSegments(7, bytes(0xff), bytes(0x81, 0x01))));
        channel.runPendingTasks();

        assertFalse(channel.isActive());
        assertNull(channel.readInbound());
    }

    @Test
    void closesChannelOnUnsupportedOversizedDeclaredLength() {
        EmbeddedChannel channel = channelForProtocol(7);

        assertFalse(channel.writeInbound(muxSegment(7, bytes(0x5a, 0xff, 0xff, 0xff, 0xff))));
        channel.runPendingTasks();

        assertFalse(channel.isActive());
        assertNull(channel.readInbound());
    }

    @Test
    void closesChannelWhenIncompletePayloadExceedsConfiguredAccumulationCap() {
        EmbeddedChannel channel = new EmbeddedChannel(new MiniProtoStreamingByteToMessageDecoder(1024, new TestAgent(7)));
        byte[] hugeByteStringHeader = bytes(0x5a, 0x7f, 0xff, 0xff, 0xff);
        byte[] continuation = new byte[65535];

        assertFalse(channel.writeInbound(muxSegment(7, hugeByteStringHeader)));
        for (int i = 0; i < 200 && channel.isActive(); i++) {
            channel.writeInbound(muxSegment(7, continuation));
        }
        channel.runPendingTasks();

        assertFalse(channel.isActive());
        assertNull(channel.readInbound());
    }

    @Test
    void closesChannelOnUnregisteredProtocol() {
        EmbeddedChannel channel = new EmbeddedChannel(new MiniProtoStreamingByteToMessageDecoder());

        assertFalse(channel.writeInbound(muxSegment(7, bytes(0x81, 0x01))));
        channel.runPendingTasks();

        assertFalse(channel.isActive());
        assertNull(channel.readInbound());
    }

    @Test
    void defaultDecoderDoesNotApplyOldEightMbHardCap() {
        EmbeddedChannel channel = channelForProtocol(7);
        byte[] hugeByteStringHeader = bytes(0x5a, 0x7f, 0xff, 0xff, 0xff);
        byte[] continuation = new byte[65535];

        assertFalse(channel.writeInbound(muxSegment(7, hugeByteStringHeader)));
        for (int i = 0; i < 130; i++) {
            assertFalse(channel.writeInbound(muxSegment(7, continuation)));
        }

        assertTrue(channel.isActive());
        assertNull(channel.readInbound());
    }

    private EmbeddedChannel channelForProtocol(int protocol) {
        return new EmbeddedChannel(new MiniProtoStreamingByteToMessageDecoder(new TestAgent(protocol)));
    }

    private ByteBuf muxSegment(int protocol, byte[] payload) {
        ByteBuf byteBuf = Unpooled.buffer();
        byteBuf.writeInt(0);
        byteBuf.writeShort(protocol);
        byteBuf.writeShort(payload.length);
        byteBuf.writeBytes(payload);
        return byteBuf;
    }

    private ByteBuf muxSegments(int protocol, byte[]... payloads) {
        ByteBuf byteBuf = Unpooled.buffer();
        for (byte[] payload : payloads) {
            byteBuf.writeInt(0);
            byteBuf.writeShort(protocol);
            byteBuf.writeShort(payload.length);
            byteBuf.writeBytes(payload);
        }
        return byteBuf;
    }

    private byte[] bytes(int... values) {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            bytes[i] = (byte) values[i];
        }
        return bytes;
    }

    private static class TestAgent extends Agent<AgentListener> {
        private final int protocolId;

        private TestAgent(int protocolId) {
            super(true);
            this.protocolId = protocolId;
            this.currentState = TestState.INSTANCE;
        }

        @Override
        public int getProtocolId() {
            return protocolId;
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
}
