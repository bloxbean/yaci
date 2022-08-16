package com.bloxbean.cardano.yaci.core.network.handlers;

import com.bloxbean.cardano.yaci.core.protocol.Segment;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MiniProtoRequestDataEncoder extends MessageToByteEncoder<Segment> {

    @Override
    protected void encode(ChannelHandlerContext ctx,
                          Segment msg, ByteBuf out) throws Exception {
        msg.serialize(out);
    }
}
