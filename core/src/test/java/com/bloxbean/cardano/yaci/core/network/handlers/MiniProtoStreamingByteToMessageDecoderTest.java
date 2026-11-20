package com.bloxbean.cardano.yaci.core.network.handlers;

import com.bloxbean.cardano.yaci.core.protocol.Segment;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.LeiosFetchAgent;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A frame's payload may hold part of a message, several messages, or a message with values after its array. */
class MiniProtoStreamingByteToMessageDecoderTest {
    private EmbeddedChannel channel = new EmbeddedChannel(new MiniProtoStreamingByteToMessageDecoder());

    @AfterEach
    void close() {
        channel.finishAndReleaseAll();
    }

    @Test
    void aMessageSplitAcrossFramesIsOneSegment() {
        receive("8204d81845");
        assertThat(segments()).isEmpty();
        receive("0102030405");
        assertThat(segments()).containsExactly("8204d818450102030405");
    }

    @Test
    void oneFrameCanHoldSeveralMessages() {
        receive("8100" + "8101");
        assertThat(segments()).containsExactly("8100", "8101");
    }

    @Test
    void valuesAfterAnArrayBelongToItsMessage() {
        receive("810001" + "8102");
        assertThat(segments()).containsExactly("810001", "8102");
    }

    @Test
    void indefiniteContainersAndChunkedArraysKeepTheirEncoding() {
        for (String message : new String[]{"9f0102ff", "829f01ff9f02ff", "82bf0001ff00"}) {
            receive(message);
            assertThat(segments()).containsExactly(message);
        }
    }

    // Every protocol is framed by its encoding, not decoded and re-encoded: a message is the bytes received.

    @Test
    void aMessageKeepsANonMinimalEncoding() {
        // block fetch [4, h'0102'] with the array, the uint and the string length written longer than needed
        String message = "9802" + "1804" + "590002" + "0102";
        receive(message);
        assertThat(segments()).containsExactly(message);
    }

    @Test
    void aValueAfterAnArrayArrivingInALaterFrameStillBelongsToItsMessage() {
        receive("8100" + "19"); //a two-byte uint follows the array: wait for it
        assertThat(segments()).isEmpty();
        receive("0005" + "8101");
        assertThat(segments()).containsExactly("8100190005", "8101");
    }

    @Test
    void fragmentsOfBothDirectionsOfAProtocolNeverMix() {
        receive(2, "8202a1");
        receive(2 | 0x8000, "8204");
        receive(2, "0102");
        receive(2 | 0x8000, "80");
        assertThat(segmentsOf()).containsExactly("2:8202a10102", "2:820480");
    }

    @Test
    void aMessageArrivingOneByteAFrameIsOneSegmentOfTheSameBytes() {
        // [4, 24(h'..')] with an indefinite map and a float inside the tagged bytes' neighbour
        String message = "83" + "04" + "d8185820" + "ab".repeat(32) + "bf01f93c00ff";
        for (int i = 0; i < message.length(); i += 2)
            receive(message.substring(i, i + 2));
        assertThat(segments()).containsExactly(message);
    }

    // Leios (ADR 0016 D2): protocols 18 and 19 carry bytes the receiver hashes, so they are framed as received.

    @Test
    void aLeiosMessageKeepsANonMinimalEncoding() {
        // [2, {h'00..': 5}] with the array, the map and the uint written in longer forms than needed
        String message = "9802" + "1802" + "b90001" + "5820" + "00".repeat(32) + "190005";
        receive(19, message);
        assertThat(segments()).containsExactly(message);
    }

    @Test
    void aLeiosMessageSplitAcrossFramesAndInterleavedWithAnotherProtocolFromTheInitiator() {
        splitAndInterleaved(0);
    }

    @Test
    void aLeiosMessageSplitAcrossFramesAndInterleavedWithAnotherProtocolFromTheResponder() {
        splitAndInterleaved(0x8000);
    }

    private void splitAndInterleaved(int direction) {
        receive(19 | direction, "8202a1");
        receive(3 | direction, "8100");
        receive(19 | direction, "0102");
        assertThat(segmentsOf()).containsExactly("3:8100", "19:8202a10102");
    }

    @Test
    void eachLeiosFetchFrameShowsProgressBeforeItsMessageIsComplete() throws Exception {
        LeiosFetchAgent agent = new LeiosFetchAgent();
        channel = new EmbeddedChannel(new MiniProtoStreamingByteToMessageDecoder(agent));
        long created = agent.getLastSegmentNanos();

        Thread.sleep(2);
        receive(3 | 0x8000, "8100"); //another protocol: no LeiosFetch progress
        receive(19, "8202a1"); //the initiator's direction: not a reply
        receive(19 | 0x8000, ""); //an empty segment
        assertThat(agent.getLastSegmentNanos()).isEqualTo(created);

        receive(19 | 0x8000, "8204"); //part of a LeiosFetch reply
        assertThat(segments()).containsExactly("8100");
        long first = agent.getLastSegmentNanos();
        assertThat(first - created).isPositive();

        Thread.sleep(2);
        receive(19 | 0x8000, "80");
        assertThat(agent.getLastSegmentNanos() - first).isPositive();
        assertThat(segments()).containsExactly("820480");
    }

    @Test
    void fragmentsOfBothDirectionsOfOneLeiosProtocolNeverMix() {
        receive(19, "8202a1");
        receive(19 | 0x8000, "8204");
        receive(19, "0102");
        receive(19 | 0x8000, "80");
        assertThat(segmentsOf()).containsExactly("19:8202a10102", "19:820480");
    }

    @Test
    void aDrainedLeiosStreamDropsALargeBuffer() {
        MiniProtoStreamingByteToMessageDecoder.CborFrameStream stream = new MiniProtoStreamingByteToMessageDecoder.CborFrameStream(false);
        byte[] big = new byte[2 * 1024 * 1024 + 5];
        big[0] = 0x5a; //a byte string of 2 MiB
        big[1] = 0x00;
        big[2] = 0x20;
        big[3] = 0x00;
        big[4] = 0x00;
        stream.append(big);
        List<Object> out = new ArrayList<>();
        stream.frame(19, 0, out);

        assertThat(out).hasSize(1);
        assertThat(stream.remaining()).isZero();
        assertThat(stream.capacity()).isZero();
    }

    @Test
    void aLeiosStreamBelowTheLimitWithoutACompleteItemWaits() {
        channel = new EmbeddedChannel(new MiniProtoStreamingByteToMessageDecoder(100));
        receive(19, "5a00010000" + "00".repeat(94)); //99 bytes of a 65,536-byte string
        assertThat(segmentsOf()).isEmpty();
        assertThat(channel.isOpen()).isTrue();
        channel.checkException();
    }

    @Test
    void aLeiosStreamAtTheLimitWithoutACompleteItemClosesTheConnection() {
        channel = new EmbeddedChannel(new MiniProtoStreamingByteToMessageDecoder(100));
        receive(19, "5a00010000" + "00".repeat(94));
        assertThatThrownBy(() -> receive(19, "00")) //100 bytes: the decoder fires its exception
                .isInstanceOf(DecoderException.class).hasMessageContaining("100 bytes");
        assertThat(segmentsOf()).isEmpty();
        assertThat(channel.isOpen()).isFalse();
    }

    @Test
    void aCompleteLeiosMessageThenAPartialOne() {
        receive(18, "820480" + "8204");
        assertThat(segmentsOf()).containsExactly("18:820480");
        receive(18, "a0");
        assertThat(segmentsOf()).containsExactly("18:8204a0");
    }

    @Test
    void aLeiosMessageFromTheResponderSideIsFramedTheSameWay() {
        receive(19 + 32768, "8202" + "9f01ff");
        assertThat(segmentsOf()).containsExactly("19:82029f01ff");
    }

    @Test
    void valuesAfterAnArrayAreTheirOwnMessagesOnALeiosProtocol() {
        // unlike the other protocols, a Leios message is one CBOR item
        receive(19, "810001");
        assertThat(segmentsOf()).containsExactly("19:8100", "19:01");
    }

    private void receive(String payloadHex) {
        receive(3, payloadHex);
    }

    private void receive(int protocol, String payloadHex) {
        byte[] payload = HexUtil.decodeHexString(payloadHex);
        byte[] frame = new byte[8 + payload.length];
        frame[4] = (byte) (protocol >> 8);
        frame[5] = (byte) protocol;
        frame[6] = (byte) (payload.length >> 8);
        frame[7] = (byte) payload.length;
        System.arraycopy(payload, 0, frame, 8, payload.length);
        channel.writeInbound(Unpooled.wrappedBuffer(frame));
    }

    private List<String> segmentsOf() {
        List<String> segments = new ArrayList<>();
        for (Segment segment = channel.readInbound(); segment != null; segment = channel.readInbound()) {
            segments.add(segment.getProtocol() + ":" + HexUtil.encodeHexString(segment.getPayload()));
        }
        return segments;
    }

    private List<String> segments() {
        List<String> segments = new ArrayList<>();
        for (Segment segment = channel.readInbound(); segment != null; segment = channel.readInbound()) {
            segments.add(HexUtil.encodeHexString(segment.getPayload()));
        }
        return segments;
    }
}
