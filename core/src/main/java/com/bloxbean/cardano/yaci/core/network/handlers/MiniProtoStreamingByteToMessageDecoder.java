package com.bloxbean.cardano.yaci.core.network.handlers;

import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.MajorType;
import com.bloxbean.cardano.client.crypto.bip32.util.BytesUtil;
import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.yaci.core.protocol.Segment;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosProtocolConstants;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.LeiosFetchAgent;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.ReplayingDecoder;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

@Slf4j
public class MiniProtoStreamingByteToMessageDecoder
        extends ReplayingDecoder<Segment> {
    private final Map<Integer, ProtocolChannel> protocolChannelMap;
    private final Map<Integer, LeiosStream> leiosStreams = new HashMap<>();
    private final int maxIncompleteLeiosBytes;
    /** The connection's LeiosFetch agent, told of each LeiosFetch segment received; null on other connections. */
    private final LeiosFetchAgent leiosFetchAgent;

    public MiniProtoStreamingByteToMessageDecoder(Agent... agents) {
        this(MAX_INCOMPLETE_LEIOS_BYTES, agents);
    }

    MiniProtoStreamingByteToMessageDecoder(int maxIncompleteLeiosBytes, Agent... agents) {
        this.maxIncompleteLeiosBytes = maxIncompleteLeiosBytes;
        protocolChannelMap = new HashMap<>();
        protocolChannelMap.put(0, new ProtocolChannel()); //For handshake channel
        LeiosFetchAgent fetchAgent = null;
        for (Agent agent: agents) {
            protocolChannelMap.put(agent.getProtocolId(), new ProtocolChannel());
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

            ProtocolChannel protocolChannel = getProtocolChannel(protocol);
            if (protocolChannel == null) {
                protocolChannel = new ProtocolChannel();
                protocolChannelMap.put(protocol, protocolChannel);
            }
            if (protocol == LeiosProtocolConstants.LEIOS_NOTIFY_PROTOCOL_ID
                    || protocol == LeiosProtocolConstants.LEIOS_FETCH_PROTOCOL_ID) {
                // Leios messages carry bytes the receiver hashes: frame them by their encoding, never re-encode.
                // one stream per protocol and direction: the two directions' fragments never mix
                int streamKey = responder ? protocol | 0x8000 : protocol;
                LeiosStream stream = leiosStreams.computeIfAbsent(streamKey, k -> new LeiosStream());
                stream.append(payload);
                if (protocol == LeiosProtocolConstants.LEIOS_FETCH_PROTOCOL_ID && responder && payloadLen > 0
                        && leiosFetchAgent != null)
                    leiosFetchAgent.segmentReceived(); //progress of a reply that may span many segments
                stream.frame(protocol, timestamp, out);
                if (stream.remaining() >= maxIncompleteLeiosBytes) {
                    // CCL cannot tell truncated from malformed CBOR: this much without a complete item is malformed.
                    int incomplete = stream.remaining();
                    log.error("Protocol {}: {} bytes without a complete CBOR item, closing the connection",
                            protocol, incomplete);
                    leiosStreams.remove(streamKey);
                    in.markReaderIndex();
                    ctx.fireExceptionCaught(new DecoderException("Protocol " + protocol + ": " + incomplete
                            + " bytes without a complete CBOR item"));
                    ctx.close();
                    return;
                }
                in.markReaderIndex();
                return;
            }
            byte[] bytes = protocolChannel != null? protocolChannel.getBytes(): new byte[0];

            bytes = BytesUtil.merge(bytes, payload);
            try {
                while (true && bytes.length != 0) {
                    DataItem[] diList = CborSerializationUtil.deserialize(bytes);
                    //To handle. When multiple dataitems and non array data items which are part of message
                    //Exp. Local State Query : Current Protocol Param's maxCollateralInputs always comes as a separate
                    //DataItem.
                    //Check till any non-array DataItem, otherwise break and the next array DI will be handled in next
                    //iteration.
                    byte[] segmentBytes;
                    List<DataItem> finalDIs = null;
                    if (diList.length > 1) {
                        //ArrayList with initialSize is used instead of LinkedList to save time in .size() call later.
                        //This is critical for BlockFetch usecase
                        finalDIs = new ArrayList<>(3);

                        int i = 0;
                        //check for array
                        for (DataItem di: diList) {
                            if (i == 0) {
                                finalDIs.add(diList[0]);
                                i++;
                                continue;
                            }

                            if (di.getMajorType() == MajorType.ARRAY)
                                break;
                            else {
                                finalDIs.add(di);
                            }
                        }
                    }

                    if (finalDIs != null && finalDIs.size() > 1) //These items are considered part of the same message.
                        segmentBytes = CborSerializationUtil.serialize(finalDIs.toArray(new DataItem[0]), false);
                    else
                        segmentBytes = CborSerializationUtil.serialize(diList[0], false);

                    Segment segment = Segment.builder()
                            .timestamp(timestamp)
                            .protocol((short) protocol)
                            .payload(segmentBytes)
                            .build();

                    int len = segmentBytes.length;

                    out.add(segment);

                    if (bytes.length > len) {
                        bytes = Arrays.copyOfRange(bytes, len, bytes.length);
                    } else
                        bytes = new byte[0];
                }
                protocolChannel.setBytes(bytes);
                in.markReaderIndex();
                return;

            } catch (Exception e) {
                protocolChannel.setBytes(bytes);
                return;
            }
        } catch (Exception e) {
            log.error("Decoding error", e);
        }
    }

    private ProtocolChannel getProtocolChannel(int protocol) {
        ProtocolChannel protocolChannel = protocolChannelMap.get(protocol);
        return protocolChannel;
    }


    /**
     * The bytes received on one Leios protocol and not yet framed. Appends are amortized (the buffer doubles), and
     * framing walks the bytes by offset, so a large message arriving in many segments is copied once per segment, not
     * once per segment for everything held so far.
     */
    static final class LeiosStream {
        static final int RETAINED_CAPACITY = 1024 * 1024;
        private byte[] buffer = new byte[0];
        private int start;
        private int end;

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

        /** Emits each complete CBOR item held, as received; an incomplete one waits for the next segment. */
        void frame(int protocol, int timestamp, List<Object> out) {
            while (start < end) {
                int stop;
                try {
                    stop = CborSpan.skip(buffer, start, end);
                } catch (RuntimeException incomplete) {
                    break;
                }
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

        int remaining() {
            return end - start;
        }

        int capacity() {
            return buffer.length;
        }
    }
}
