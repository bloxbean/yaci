package com.bloxbean.cardano.yaci.core.network.handlers;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.AgentListener;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.Segment;
import com.bloxbean.cardano.yaci.core.protocol.State;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Property tests for the mux-level CBOR framing path.
 *
 * <p>The production decoder does not understand Cardano blocks, transactions, or any
 * protocol-specific CDDL at this layer. Its narrow responsibility is to buffer mux payload
 * bytes per mini-protocol, identify complete CBOR message boundaries, and emit the exact
 * original bytes without decode/re-encode normalization.
 *
 * <p>These tests generate valid CBOR messages, split their byte stream into arbitrary mux
 * segment payloads, feed those segments through the Netty decoder, and assert that the
 * emitted {@link Segment} payloads are byte-for-byte identical to the generated messages.
 * This catches boundary bugs that fixed example tests can miss, especially around nested
 * CBOR, indefinite-length items, tag-wrapped arrays, and interleaved protocol buffers.
 */
class MiniProtoStreamingByteToMessageDecoderPropertyTest {
    private static final int PROTOCOL_A = 7;
    private static final int PROTOCOL_B = 9;

    /**
     * A single mini-protocol stream may be split at arbitrary mux segment boundaries.
     *
     * <p>The invariant is stronger than "the decoder parsed something": after arbitrary
     * chunking, every emitted payload must exactly match the original generated message
     * bytes. This protects non-canonical but valid CBOR encodings and hash-sensitive
     * opaque payloads from accidental re-encoding or off-by-one slicing.
     */
    @Property(tries = 200)
    void emitsExactMessageBytesForAnyMuxSegmentation(@ForAll long seed,
                                                     @ForAll @IntRange(min = 1, max = 50) int messageCount,
                                                     @ForAll @IntRange(min = 1, max = 256) int maxChunkSize) {
        Random random = new Random(seed);
        List<byte[]> messages = randomMuxMessages(random, messageCount);
        List<byte[]> chunks = splitStream(concat(messages), random, maxChunkSize);
        EmbeddedChannel channel = new EmbeddedChannel(
                new MiniProtoStreamingByteToMessageDecoder(new TestAgent(PROTOCOL_A)));

        List<Segment> emitted = writeChunks(channel, PROTOCOL_A, chunks);

        assertTrue(channel.isActive(), "seed=" + seed);
        assertPayloads(messages, emitted, seed);
    }

    /**
     * Mux carries several mini-protocols over the same connection.
     *
     * <p>Each protocol id must have its own accumulation buffer. A partial message on one
     * protocol must not block, consume, or contaminate message boundaries for another
     * protocol. This property interleaves chunks from two protocol streams and verifies
     * that each stream is reconstructed independently.
     */
    @Property(tries = 200)
    void keepsIndependentBuffersForInterleavedProtocols(@ForAll long seed,
                                                       @ForAll @IntRange(min = 1, max = 30) int protocolAMessageCount,
                                                       @ForAll @IntRange(min = 1, max = 30) int protocolBMessageCount,
                                                       @ForAll @IntRange(min = 1, max = 128) int maxChunkSize) {
        Random random = new Random(seed);
        List<byte[]> protocolAMessages = randomMuxMessages(random, protocolAMessageCount);
        List<byte[]> protocolBMessages = randomMuxMessages(random, protocolBMessageCount);
        List<byte[]> protocolAChunks = splitStream(concat(protocolAMessages), random, maxChunkSize);
        List<byte[]> protocolBChunks = splitStream(concat(protocolBMessages), random, maxChunkSize);
        EmbeddedChannel channel = new EmbeddedChannel(
                new MiniProtoStreamingByteToMessageDecoder(new TestAgent(PROTOCOL_A), new TestAgent(PROTOCOL_B)));

        List<Segment> emitted = new ArrayList<>();
        int a = 0;
        int b = 0;
        while (a < protocolAChunks.size() || b < protocolBChunks.size()) {
            boolean writeA = b >= protocolBChunks.size() || (a < protocolAChunks.size() && random.nextBoolean());
            if (writeA) {
                channel.writeInbound(muxSegment(PROTOCOL_A, protocolAChunks.get(a++)));
            } else {
                channel.writeInbound(muxSegment(PROTOCOL_B, protocolBChunks.get(b++)));
            }
            drain(channel, emitted);
        }

        assertTrue(channel.isActive(), "seed=" + seed);
        assertPayloads(protocolAMessages, payloadsForProtocol(emitted, PROTOCOL_A), seed);
        assertPayloads(protocolBMessages, payloadsForProtocol(emitted, PROTOCOL_B), seed);
    }

    /**
     * Structurally malformed CBOR should poison the mux decoder for the connection.
     *
     * <p>This property is intentionally small in scope. It does not try to prove DoS or
     * resource-limit policy. It only protects the reject path invariant: after a valid
     * message prefix, a malformed CBOR fragment must close the channel and must not emit
     * any extra payload bytes after the stream is known to be invalid.
     */
    @Property(tries = 100)
    void malformedCborClosesChannelWithoutEmittingGarbage(@ForAll long seed,
                                                          @ForAll @IntRange(min = 1, max = 128) int maxChunkSize) {
        Random random = new Random(seed);
        byte[] validMessage = randomMuxMessage(random);
        byte[] malformed = randomMalformedCbor(random);
        List<byte[]> malformedChunks = splitStream(malformed, random, maxChunkSize);
        EmbeddedChannel channel = new EmbeddedChannel(
                new MiniProtoStreamingByteToMessageDecoder(new TestAgent(PROTOCOL_A)));

        List<Segment> emitted = withDecoderLoggingDisabled(() -> {
            List<Segment> segments = writeChunks(channel, PROTOCOL_A, List.of(validMessage));
            segments.addAll(writeChunksUntilInactive(channel, PROTOCOL_A, malformedChunks));
            channel.runPendingTasks();
            return segments;
        });

        assertFalse(channel.isActive(), "seed=" + seed);
        assertEquals(1, emitted.size(), "seed=" + seed);
        assertArrayEquals(validMessage, emitted.get(0).getPayload(), "seed=" + seed);
    }

    /**
     * Generates mux message payloads shaped like normal Cardano mini-protocol messages:
     * a top-level CBOR array, sometimes wrapped in a tag. The array can contain arbitrary
     * nested valid CBOR values.
     */
    private List<byte[]> randomMuxMessages(Random random, int messageCount) {
        List<byte[]> messages = new ArrayList<>(messageCount);
        for (int i = 0; i < messageCount; i++) {
            messages.add(randomMuxMessage(random));
        }
        return messages;
    }

    private byte[] randomMuxMessage(Random random) {
        byte[] array = random.nextBoolean()
                ? randomArray(random, 0)
                : randomIndefiniteArray(random, 0);

        if (random.nextInt(4) == 0) {
            return concat(encodeUnsigned(6, 1_000 + random.nextInt(10_000)), array);
        } else {
            return array;
        }
    }

    /**
     * Generates valid CBOR values for message contents. It intentionally includes nested
     * arrays/maps, indefinite-length containers, indefinite byte/text strings, and tags
     * because those are the cases where boundary scanning is easy to get wrong.
     */
    private byte[] randomCbor(Random random, int depth) {
        int choice = depth > 3 ? random.nextInt(5) : random.nextInt(12);
        return switch (choice) {
            case 0 -> encodeUnsigned(0, randomIntegerArgument(random));
            case 1 -> encodeUnsigned(1, randomIntegerArgument(random));
            case 2 -> randomByteString(random);
            case 3 -> randomTextString(random);
            case 4 -> randomSimple(random);
            case 5 -> randomArray(random, depth);
            case 6 -> randomMap(random, depth);
            case 7 -> randomIndefiniteArray(random, depth);
            case 8 -> randomIndefiniteMap(random, depth);
            case 9 -> concat(encodeUnsigned(6, 1_000 + random.nextInt(10_000)), randomCbor(random, depth + 1));
            case 10 -> randomIndefiniteByteString(random);
            default -> randomIndefiniteTextString(random);
        };
    }

    private long randomIntegerArgument(Random random) {
        return switch (random.nextInt(5)) {
            case 0 -> random.nextInt(24);
            case 1 -> random.nextInt(256);
            case 2 -> random.nextInt(65_536);
            case 3 -> random.nextLong(1L << 32);
            default -> random.nextLong(Long.MAX_VALUE);
        };
    }

    private byte[] randomByteString(Random random) {
        byte[] data = new byte[random.nextInt(32)];
        random.nextBytes(data);
        return concat(encodeUnsigned(2, data.length), data);
    }

    private byte[] randomTextString(Random random) {
        byte[] data = randomAsciiBytes(random);
        return concat(encodeUnsigned(3, data.length), data);
    }

    private byte[] randomSimple(Random random) {
        return switch (random.nextInt(8)) {
            case 0 -> bytes(0xf4);
            case 1 -> bytes(0xf5);
            case 2 -> bytes(0xf6);
            case 3 -> bytes(0xf7);
            case 4 -> bytes(0xf8, 32 + random.nextInt(224));
            case 5 -> concat(bytes(0xf9), randomBytes(random, 2));
            case 6 -> concat(bytes(0xfa), randomBytes(random, 4));
            default -> concat(bytes(0xfb), randomBytes(random, 8));
        };
    }

    private byte[] randomArray(Random random, int depth) {
        int length = random.nextInt(5);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(encodeUnsigned(4, length));
        for (int i = 0; i < length; i++) {
            out.writeBytes(randomCbor(random, depth + 1));
        }
        return out.toByteArray();
    }

    private byte[] randomMap(Random random, int depth) {
        int length = random.nextInt(5);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(encodeUnsigned(5, length));
        for (int i = 0; i < length; i++) {
            out.writeBytes(randomCbor(random, depth + 1));
            out.writeBytes(randomCbor(random, depth + 1));
        }
        return out.toByteArray();
    }

    private byte[] randomIndefiniteArray(Random random, int depth) {
        int length = random.nextInt(5);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x9f);
        for (int i = 0; i < length; i++) {
            out.writeBytes(randomCbor(random, depth + 1));
        }
        out.write(0xff);
        return out.toByteArray();
    }

    private byte[] randomIndefiniteMap(Random random, int depth) {
        int length = random.nextInt(5);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xbf);
        for (int i = 0; i < length; i++) {
            out.writeBytes(randomCbor(random, depth + 1));
            out.writeBytes(randomCbor(random, depth + 1));
        }
        out.write(0xff);
        return out.toByteArray();
    }

    private byte[] randomIndefiniteByteString(Random random) {
        int chunks = random.nextInt(5);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x5f);
        for (int i = 0; i < chunks; i++) {
            out.writeBytes(randomByteString(random));
        }
        out.write(0xff);
        return out.toByteArray();
    }

    private byte[] randomIndefiniteTextString(Random random) {
        int chunks = random.nextInt(5);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x7f);
        for (int i = 0; i < chunks; i++) {
            out.writeBytes(randomTextString(random));
        }
        out.write(0xff);
        return out.toByteArray();
    }

    private byte[] randomAsciiBytes(Random random) {
        byte[] data = new byte[random.nextInt(32)];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) ('a' + random.nextInt(26));
        }
        return data;
    }

    private byte[] randomMalformedCbor(Random random) {
        return switch (random.nextInt(6)) {
            case 0 -> bytes(0xff); // Top-level break byte.
            case 1 -> bytes(0x82, 0x01, 0xff); // Break byte inside a definite array.
            case 2 -> bytes(0xbf, 0x01, 0xff); // Indefinite map ends after key without value.
            case 3 -> bytes(0x5f, 0x5f, 0x40, 0xff, 0xff); // Nested indefinite byte-string chunk.
            case 4 -> bytes(0x5f, 0x01, 0xff); // Indefinite byte string contains a non-byte-string chunk.
            default -> bytes(0xfc); // Reserved simple-value additional information.
        };
    }

    private byte[] randomBytes(Random random, int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }

    /**
     * Splits a byte stream into arbitrary mux payload chunks. The decoder must behave the
     * same whether a CBOR message arrives in one segment, byte-by-byte, or across several
     * uneven segments.
     */
    private List<byte[]> splitStream(byte[] bytes, Random random, int maxChunkSize) {
        List<byte[]> chunks = new ArrayList<>();
        int offset = 0;
        while (offset < bytes.length) {
            int maxSize = Math.min(maxChunkSize, bytes.length - offset);
            int chunkSize = 1 + random.nextInt(maxSize);
            chunks.add(Arrays.copyOfRange(bytes, offset, offset + chunkSize));
            offset += chunkSize;
        }
        return chunks;
    }

    private List<Segment> writeChunks(EmbeddedChannel channel, int protocol, List<byte[]> chunks) {
        List<Segment> emitted = new ArrayList<>();
        for (byte[] chunk : chunks) {
            channel.writeInbound(muxSegment(protocol, chunk));
            drain(channel, emitted);
        }
        return emitted;
    }

    private List<Segment> writeChunksUntilInactive(EmbeddedChannel channel, int protocol, List<byte[]> chunks) {
        List<Segment> emitted = new ArrayList<>();
        for (byte[] chunk : chunks) {
            if (!channel.isActive())
                break;

            channel.writeInbound(muxSegment(protocol, chunk));
            drain(channel, emitted);
        }
        return emitted;
    }

    private List<Segment> withDecoderLoggingDisabled(SegmentSupplier supplier) {
        LoggingControl loggingControl = disableDecoderLogging();
        try {
            return supplier.get();
        } finally {
            if (loggingControl != null)
                loggingControl.restore();
        }
    }

    private LoggingControl disableDecoderLogging() {
        try {
            Class<?> loggerClass = Class.forName("org.apache.log4j.Logger");
            Class<?> levelClass = Class.forName("org.apache.log4j.Level");
            Method getLogger = loggerClass.getMethod("getLogger", Class.class);
            Method getLevel = loggerClass.getMethod("getLevel");
            Method setLevel = loggerClass.getMethod("setLevel", levelClass);
            Object logger = getLogger.invoke(null, MiniProtoStreamingByteToMessageDecoder.class);
            Object previousLevel = getLevel.invoke(logger);
            Object offLevel = levelClass.getField("OFF").get(null);

            setLevel.invoke(logger, offLevel);
            return () -> {
                try {
                    setLevel.invoke(logger, new Object[]{previousLevel});
                } catch (ReflectiveOperationException e) {
                    // Best-effort test log suppression only.
                }
            };
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private void drain(EmbeddedChannel channel, List<Segment> emitted) {
        Segment segment;
        while ((segment = channel.readInbound()) != null) {
            emitted.add(segment);
        }
    }

    private List<Segment> payloadsForProtocol(List<Segment> emitted, int protocol) {
        return emitted.stream()
                .filter(segment -> segment.getProtocol() == protocol)
                .toList();
    }

    private void assertPayloads(List<byte[]> expected, List<Segment> actual, long seed) {
        assertEquals(expected.size(), actual.size(), "seed=" + seed);
        for (int i = 0; i < expected.size(); i++) {
            assertArrayEquals(expected.get(i), actual.get(i).getPayload(), "seed=" + seed + ", message=" + i);
        }
    }

    private ByteBuf muxSegment(int protocol, byte[] payload) {
        ByteBuf byteBuf = Unpooled.buffer();
        byteBuf.writeInt(0);
        byteBuf.writeShort(protocol);
        byteBuf.writeShort(payload.length);
        byteBuf.writeBytes(payload);
        return byteBuf;
    }

    private byte[] encodeUnsigned(int majorType, long argument) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int prefix = majorType << 5;
        if (argument < 24) {
            out.write(prefix | (int) argument);
        } else if (argument <= 0xff) {
            out.write(prefix | 24);
            out.write((int) argument);
        } else if (argument <= 0xffff) {
            out.write(prefix | 25);
            out.write((int) (argument >>> 8));
            out.write((int) argument);
        } else if (argument <= 0xffffffffL) {
            out.write(prefix | 26);
            for (int i = 3; i >= 0; i--) {
                out.write((int) (argument >>> (8 * i)));
            }
        } else {
            out.write(prefix | 27);
            for (int i = 7; i >= 0; i--) {
                out.write((int) (argument >>> (8 * i)));
            }
        }
        return out.toByteArray();
    }

    private byte[] concat(List<byte[]> chunks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] chunk : chunks) {
            out.writeBytes(chunk);
        }
        return out.toByteArray();
    }

    private byte[] concat(byte[]... chunks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] chunk : chunks) {
            out.writeBytes(chunk);
        }
        return out.toByteArray();
    }

    private byte[] bytes(int... values) {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            bytes[i] = (byte) values[i];
        }
        return bytes;
    }

    private interface SegmentSupplier {
        List<Segment> get();
    }

    private interface LoggingControl {
        void restore();
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
