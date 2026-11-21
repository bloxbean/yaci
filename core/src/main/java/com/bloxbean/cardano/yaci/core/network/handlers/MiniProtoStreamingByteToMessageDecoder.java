package com.bloxbean.cardano.yaci.core.network.handlers;

import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.client.exception.CborRuntimeException;
import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.Segment;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosProtocolConstants;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.LeiosFetchAgent;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.ReplayingDecoder;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * Splits the mux frames of every mini-protocol into messages by their CBOR encoding (CCL's {@link CborSpan#skip}),
 * and hands each message on as the bytes received: nothing is decoded or re-encoded here.
 */
@Slf4j
public class MiniProtoStreamingByteToMessageDecoder
        extends ReplayingDecoder<Segment> {
    private static final int MAJOR_ARRAY = 4;
    private static final int MAJOR_TAG = 6;

    private final Map<Integer, CborFrameStream> streams = new HashMap<>();
    /** The protocols an agent handles, and the handshake: only their streams stay open once drained. */
    private final Set<Integer> registeredProtocols = new HashSet<>();
    private final int maxIncompleteLeiosBytes;
    /** The connection's LeiosFetch agent, told of each LeiosFetch segment received; null on other connections. */
    private final LeiosFetchAgent leiosFetchAgent;

    public MiniProtoStreamingByteToMessageDecoder(Agent... agents) {
        this(MAX_INCOMPLETE_LEIOS_BYTES, agents);
    }

    MiniProtoStreamingByteToMessageDecoder(int maxIncompleteLeiosBytes, Agent... agents) {
        this.maxIncompleteLeiosBytes = maxIncompleteLeiosBytes;
        registeredProtocols.add(0); //handshake
        LeiosFetchAgent fetchAgent = null;
        for (Agent agent: agents) {
            registeredProtocols.add(agent.getProtocolId());
            if (agent instanceof LeiosFetchAgent leiosFetch)
                fetchAgent = leiosFetch;
        }
        this.leiosFetchAgent = fetchAgent;
    }

    /** A Leios protocol holding this many bytes without a complete CBOR item is malformed. */
    static final int MAX_INCOMPLETE_LEIOS_BYTES = 64 * 1024 * 1024;

    @Override
    protected void decode(ChannelHandlerContext ctx,
                          ByteBuf in, List<Object> out) {
        try {
            int timestamp = (int) in.readUnsignedInt();
            int protocol = in.readShort();
            boolean responder = protocol < 0; //the mux direction bit

            //TODO -- check the following logic for unsigned
            if (protocol < 0)
                protocol = protocol + 32768;
            int payloadLen = in.readUnsignedShort();

            byte[] payload = new byte[payloadLen];
            in.readBytes(payload);

            if (log.isTraceEnabled()) {
                log.trace("Receive: Segment protocol >> " + protocol);
                log.trace("Receive: Segment Timestamp >> " + timestamp);
                log.trace("Receive: Segment len >> " + payloadLen);
            }

            boolean leios = protocol == LeiosProtocolConstants.LEIOS_NOTIFY_PROTOCOL_ID
                    || protocol == LeiosProtocolConstants.LEIOS_FETCH_PROTOCOL_ID;
            // one stream per protocol and direction: the two directions' fragments never mix
            int streamKey = responder ? protocol | 0x8000 : protocol;
            CborFrameStream stream = streams.computeIfAbsent(streamKey, k -> new CborFrameStream(!leios));
            stream.append(payload);
            if (protocol == LeiosProtocolConstants.LEIOS_FETCH_PROTOCOL_ID && responder && payloadLen > 0
                    && leiosFetchAgent != null)
                leiosFetchAgent.segmentReceived(); //progress of a reply that may span many segments
            stream.frame(protocol, timestamp, out);
            if (stream.remaining() == 0 && !registeredProtocols.contains(protocol))
                streams.remove(streamKey); //no agent handles it: keep nothing for it
            if (leios && stream.remaining() >= maxIncompleteLeiosBytes) {
                // CCL cannot tell truncated from malformed CBOR: this much without a complete item is malformed.
                int incomplete = stream.remaining();
                log.error("Protocol {}: {} bytes without a complete CBOR item, closing the connection",
                        protocol, incomplete);
                streams.remove(streamKey);
                in.markReaderIndex();
                ctx.fireExceptionCaught(new DecoderException("Protocol " + protocol + ": " + incomplete
                        + " bytes without a complete CBOR item"));
                ctx.close();
                return;
            }
            in.markReaderIndex();
        } catch (Exception e) {
            log.error("Decoding error", e);
        }
    }

    /** The streams held: one per protocol and direction with an agent, or with a message still arriving. */
    int openStreams() {
        return streams.size();
    }

    /**
     * The bytes received on one protocol in one direction and not yet framed. Appends are amortized (the buffer
     * doubles), and framing walks the bytes by offset, so a large message arriving in many segments is copied once per
     * segment, not once per segment for everything held so far.
     */
    static final class CborFrameStream {
        static final int RETAINED_CAPACITY = 1024 * 1024;
        /**
         * Whether values after a message's CBOR item belong to that message, up to the next array: true on every
         * protocol but Leios. Older nodes (before 8.0) answered LocalStateQuery's current protocol parameters with a
         * 22-element array followed by maxCollateralInputs as a separate value; CurrentProtocolParamsQuery reads it.
         */
        private final boolean joinTrailingValues;
        private byte[] buffer = new byte[0];
        private int start;
        private int end;

        CborFrameStream(boolean joinTrailingValues) {
            this.joinTrailingValues = joinTrailingValues;
        }

        void append(byte[] payload) {
            if (end + payload.length > buffer.length) {
                int held = end - start;
                byte[] grown = held + payload.length > buffer.length
                        ? new byte[Math.max(held + payload.length, buffer.length * 2)] : buffer;
                System.arraycopy(buffer, start, grown, 0, held);
                buffer = grown;
                start = 0;
                end = held;
            }
            System.arraycopy(payload, 0, buffer, end, payload.length);
            end += payload.length;
        }

        /** Emits each complete message held, as received; an incomplete one waits for the next segment. */
        void frame(int protocol, int timestamp, List<Object> out) {
            while (start < end) {
                int stop = messageEnd();
                if (stop < 0)
                    break;
                out.add(Segment.builder()
                        .timestamp(timestamp)
                        .protocol((short) protocol)
                        .payload(Arrays.copyOfRange(buffer, start, stop))
                        .build());
                start = stop;
            }
            if (start == end) {
                start = 0;
                end = 0;
                if (buffer.length > RETAINED_CAPACITY)
                    buffer = new byte[0]; //a large message passed: do not keep its buffer
            }
        }

        /** The end of the message at {@code start}, or -1 while it is incomplete. */
        private int messageEnd() {
            int stop = skip(start);
            if (stop < 0 || !joinTrailingValues)
                return stop;
            while (stop < end) {
                int major = majorTypeAt(stop);
                if (major < 0)
                    return -1; //a tag still arriving
                if (major == MAJOR_ARRAY)
                    break;
                stop = skip(stop);
                if (stop < 0)
                    return -1; //a trailing value still arriving: wait for it with its message
            }
            return stop;
        }

        /**
         * The major type of the item at {@code offset}, looking through its tags (cbor-java reads a tagged array as an
         * array), or -1 while a tag's head is still arriving.
         */
        private int majorTypeAt(int offset) {
            while (offset < end) {
                int head = buffer[offset] & 0xff;
                int info = head & 0x1f;
                if (head >>> 5 != MAJOR_TAG || info > 27) //not a tag, or a malformed one that skip refuses
                    return head >>> 5;
                offset += info < 24 ? 1 : 1 + (1 << (info - 24));
            }
            return -1;
        }

        private int skip(int offset) {
            try {
                return CborSpan.skip(buffer, offset, end);
            } catch (CborRuntimeException incomplete) {
                return -1;
            }
        }

        int remaining() {
            return end - start;
        }

        int capacity() {
            return buffer.length;
        }
    }
}
