package com.bloxbean.cardano.yaci.core.network.handlers;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.Segment;
import com.bloxbean.cardano.yaci.core.util.CborByteScanner;
import com.bloxbean.cardano.yaci.core.util.CborByteScanner.CborSlice;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ReplayingDecoder;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

@Slf4j
public class MiniProtoStreamingByteToMessageDecoder
        extends ReplayingDecoder<Segment> {
    private static final int UNLIMITED_INCOMPLETE_BUFFER = -1;

    private final Map<Integer, ProtocolChannel> protocolChannelMap;
    private final int maxIncompleteBufferSize;
    private boolean poisoned;

    public MiniProtoStreamingByteToMessageDecoder(Agent... agents) {
        this(UNLIMITED_INCOMPLETE_BUFFER, agents);
    }

    public MiniProtoStreamingByteToMessageDecoder(int maxIncompleteBufferSize, Agent... agents) {
        this.maxIncompleteBufferSize = maxIncompleteBufferSize;
        protocolChannelMap = new HashMap<>();
        protocolChannelMap.put(0, new ProtocolChannel()); //For handshake channel
        for (Agent agent: agents) {
            protocolChannelMap.put(agent.getProtocolId(), new ProtocolChannel());
        }
    }

    @Override
    protected void decode(ChannelHandlerContext ctx,
                          ByteBuf in, List<Object> out) {
        if (poisoned) {
            in.skipBytes(actualReadableBytes());
            return;
        }

        try {
            int timestamp = (int) in.readUnsignedInt();

            int protocol = in.readShort();

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
                log.warn("Received segment for unregistered mini-protocol {}. Closing channel.", protocol);
                poisoned = true;
                ctx.close();
                return;
            }
            protocolChannel.append(payload);

            try {
                int consumedLength = emitCompleteMessages(timestamp, protocol, protocolChannel, out);
                if (consumedLength > 0)
                    protocolChannel.discardBytes(consumedLength);

                validateIncompleteBuffer(protocol, protocolChannel);
                in.markReaderIndex();
                return;

            } catch (RuntimeException | StackOverflowError e) {
                log.warn("Invalid CBOR payload for mini-protocol {}. Closing channel. {}", protocol, e.toString());
                log.debug("Invalid CBOR payload for mini-protocol " + protocol, e);
                poisoned = true;
                protocolChannel.clear();
                ctx.close();
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

    private int emitCompleteMessages(int timestamp, int protocol, ProtocolChannel protocolChannel, List<Object> out) {
        byte[] bytes = protocolChannel.getBytes();
        List<CborSlice> frames = protocolChannel.scanCompleteFrames();
        if (frames.isEmpty())
            return 0;

        int consumedLength = 0;
        int frameIndex = 0;
        while (frameIndex < frames.size()) {
            int messageStart = frames.get(frameIndex).startOffset();
            int messageEnd = frames.get(frameIndex).endOffset();
            frameIndex++;

            while (frameIndex < frames.size() && !isMessageStart(frames.get(frameIndex))) {
                messageEnd = frames.get(frameIndex).endOffset();
                frameIndex++;
            }

            if (messageEnd < bytes.length && !startsWithMessage(bytes, messageEnd))
                break;

            out.add(Segment.builder()
                    .timestamp(timestamp)
                    .protocol(protocol)
                    .payload(Arrays.copyOfRange(bytes, messageStart, messageEnd))
                    .build());
            consumedLength = messageEnd;
        }

        return consumedLength;
    }

    private void validateIncompleteBuffer(int protocol, ProtocolChannel protocolChannel) {
        if (maxIncompleteBufferSize < 0)
            return;

        int bufferedBytes = protocolChannel.getBytes().length;
        if (bufferedBytes > maxIncompleteBufferSize) {
            throw new IllegalArgumentException("Accumulated incomplete mini-protocol buffer for protocol "
                    + protocol + " exceeded " + maxIncompleteBufferSize + " bytes");
        }
    }

    private boolean startsWithMessage(byte[] bytes, int offset) {
        try {
            return CborByteScanner.untaggedMajorType(bytes, offset) == CborByteScanner.MAJOR_TYPE_ARRAY;
        } catch (CborByteScanner.IncompleteCborException e) {
            return false;
        }
    }

    private boolean isMessageStart(CborSlice frame) {
        return frame.untaggedMajorType() == CborByteScanner.MAJOR_TYPE_ARRAY;
    }

}
