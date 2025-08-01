package com.bloxbean.cardano.yaci.core.network.handlers;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.MajorType;
import com.bloxbean.cardano.client.crypto.bip32.util.BytesUtil;
import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.Segment;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ReplayingDecoder;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

@Slf4j
public class MiniProtoStreamingByteToMessageDecoder
        extends ReplayingDecoder<Segment> {
    private final Map<Integer, ProtocolChannel> protocolChannelMap;

    public MiniProtoStreamingByteToMessageDecoder(Agent... agents) {
        protocolChannelMap = new HashMap<>();
        protocolChannelMap.put(0, new ProtocolChannel()); //For handshake channel
        for (Agent agent: agents) {
            protocolChannelMap.put(agent.getProtocolId(), new ProtocolChannel());
        }
    }

    @Override
    protected void decode(ChannelHandlerContext ctx,
                          ByteBuf in, List<Object> out) {
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
                protocolChannel = new ProtocolChannel();
                protocolChannelMap.put(protocol, protocolChannel);
            }
            byte[] bytes = protocolChannel != null? protocolChannel.getBytes(): new byte[0];

            bytes = BytesUtil.merge(bytes, payload);
            try {
                while (true && bytes.length != 0) {
                    //TODO -- Remove later after testing
//                    DataItem di = CborDecoder.decode(bytes).get(0);
//                    byte[] segmentBytes = CborSerializationUtil.serialize(di);


                    List<DataItem> diList = CborDecoder.decode(bytes);
                    //To handle. When multiple dataitems and non array data items which are part of message
                    //Exp. Local State Query : Current Protocol Param's maxCollateralInputs always comes as a separate
                    //DataItem.
                    //Check till any non-array DataItem, otherwise break and the next array DI will be handled in next
                    //iteration.
                    byte[] segmentBytes;
                    List<DataItem> finalDIs = null;
                    if (diList.size() > 1) {
                        //ArrayList with initialSize is used instead of LinkedList to save time in .size() call later.
                        //This is critical for BlockFetch usecase
                        finalDIs = new ArrayList<>(3);

                        int i = 0;
                        //check for array
                        for (DataItem di: diList) {
                            if (i == 0) {
                                finalDIs.add(diList.get(0));
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
                        segmentBytes = CborSerializationUtil.serialize(diList.get(0), false);

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

}
