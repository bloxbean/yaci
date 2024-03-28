package com.bloxbean.cardano.yaci.core.network.handlers;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.Segment;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;

@Slf4j
public class MiniProtoClientInboundHandler extends ChannelInboundHandlerAdapter {
    private final Agent handshakeAgent;
    private final Agent[] agents;
    public MiniProtoClientInboundHandler(Agent handshakeAgent, Agent[] agents) {
        this.handshakeAgent = handshakeAgent;
        this.agents = agents;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        try {
            Segment segment = (Segment) msg;
            if (segment.getProtocol() == handshakeAgent.getProtocolId()) {
                Message message = handshakeAgent.deserializeResponse(segment.getPayload());
                handshakeAgent.receiveResponse(message);
                // if server side, we need to send message back with accepted version
                log.info("handshakeAgent.hasAgency(): {}", handshakeAgent.hasAgency());
                if (handshakeAgent.hasAgency()) {
                    handshakeAgent.sendNextMessage();
                }
            } else {
                for (Agent agent : agents) {
                    if (!agent.isDone() && agent.getProtocolId() == segment.getProtocol()) {
                        Message message = agent.deserializeResponse(segment.getPayload());
                        agent.receiveResponse(message);
                        break;
                    }
                }
            }

            ctx.flush();
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }

    public static void main(String[] args) {
        byte[] bytes = HexUtil.decodeHexString("000001e8000000618200ac011a2d964a091980021a2d964a091980031a2d964a091980041a2d964a091980051a2d964a091980061a2d964a091980071a2d964a091980081a2d964a091980091a2d964a0919800a1a2d964a0919800b1a2d964a0919800c1a2d964a09");
        byte[] slice = Arrays.copyOfRange(bytes, 8, bytes.length);
        log.info(HexUtil.encodeHexString(slice));
    }
}
