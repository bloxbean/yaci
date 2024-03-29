package com.bloxbean.cardano.yaci.core.network.handlers;

import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.Segment;
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
        handshakeAgent.setChannel(ctx.channel());
        Arrays.stream(agents).forEach(agent -> agent.setChannel(ctx.channel()));
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

}
