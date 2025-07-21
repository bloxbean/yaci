package com.bloxbean.cardano.yaci.core.network.server.handlers;

import com.bloxbean.cardano.yaci.core.network.server.NodeServer;
import com.bloxbean.cardano.yaci.core.network.server.NodeServerSession;
import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.Message;
import com.bloxbean.cardano.yaci.core.protocol.Segment;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MiniProtoServerInboundHandler extends SimpleChannelInboundHandler<Segment> {
    private final NodeServerSession session;

    public MiniProtoServerInboundHandler(NodeServerSession session) {
        this.session = session;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        log.info("Client connected: {}", ctx.channel().remoteAddress());
        session.getHandshakeAgent().setChannel(ctx.channel());
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Segment segment) {
        try {
            log.debug("Received segment from client: Protocol {} - Length {}", segment.getProtocol(), segment.getPayload().length);

            if (segment.getProtocol() == session.getHandshakeAgent().getProtocolId()) {
                Message message = session.getHandshakeAgent().deserializeResponse(segment.getPayload());
                session.getHandshakeAgent().receiveResponse(message);

                if (!session.getHandshakeAgent().isDone() && session.getHandshakeAgent().hasAgency())
                    session.getHandshakeAgent().sendNextMessage();
            } else {
                log.debug("Routing message for protocol {} to agents", segment.getProtocol());
                boolean messageHandled = false;
                for (Agent agent : session.getAgents()) {
                    log.debug("Checking agent - protocolId: {}, isDone: {}, matches: {}",
                             agent.getProtocolId(), agent.isDone(), agent.getProtocolId() == segment.getProtocol());
                    if (!agent.isDone() && agent.getProtocolId() == segment.getProtocol()) {
                        log.debug("Message matched agent with protocol {}", agent.getProtocolId());
                        Message message = agent.deserializeResponse(segment.getPayload());
                        log.info("Deserialized message: {}", message != null ? message.getClass().getSimpleName() : "null");
                        agent.receiveResponse(message);

                        if (agent.hasAgency()) {
                            log.info("Agent has agency, sending next message: protocol id: {}", agent.getProtocolId());
                            agent.sendNextMessage();
                        } else {
                            log.info("Agent does not have agency for protocol {}", agent.getProtocolId());
                        }
                        messageHandled = true;
                        break;
                    }
                }
                if (!messageHandled) {
                    log.warn("No agent found to handle protocol {}", segment.getProtocol());
                }
            }

            ctx.flush();
        } finally {
            ReferenceCountUtil.release(segment);
        }
    }


    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Error processing client request", cause);
        ctx.close();
        session.close();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.info("Client disconnected: {}", ctx.channel().remoteAddress());

        // Remove session from NodeServer when client disconnects
        session.close();
        NodeServer.removeSession(ctx.channel());
    }

}
