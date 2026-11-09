package com.bloxbean.cardano.yaci.core.network.handlers;

import com.bloxbean.cardano.yaci.core.protocol.Segment;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A frame's payload may hold part of a message, several messages, or a message with values after its array. */
class MiniProtoStreamingByteToMessageDecoderTest {
    private final EmbeddedChannel channel = new EmbeddedChannel(new MiniProtoStreamingByteToMessageDecoder());

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

    private void receive(String payloadHex) {
        byte[] payload = HexUtil.decodeHexString(payloadHex);
        byte[] frame = new byte[8 + payload.length];
        frame[5] = 3;
        frame[7] = (byte) payload.length;
        System.arraycopy(payload, 0, frame, 8, payload.length);
        channel.writeInbound(Unpooled.wrappedBuffer(frame));
    }

    private List<String> segments() {
        List<String> segments = new ArrayList<>();
        for (Segment segment = channel.readInbound(); segment != null; segment = channel.readInbound()) {
            segments.add(HexUtil.encodeHexString(segment.getPayload()));
        }
        return segments;
    }
}
