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
    
    // Flag to prevent stale message delivery from old connections
    private volatile boolean isActive = true;
    
    public MiniProtoClientInboundHandler(Agent handshakeAgent, Agent[] agents) {
        this.handshakeAgent = handshakeAgent;
        this.agents = agents;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
    }
    
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // CRITICAL: Mark this handler as inactive immediately to prevent any further message delivery
        isActive = false;
        if (log.isDebugEnabled()) {
            log.debug("🚫 Handler marked as inactive - will block all future messages");
        }
        super.channelInactive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        try {
            // CRITICAL: Block all messages if this handler is inactive (from old connection)
            if (!isActive) {
                if (log.isDebugEnabled()) {
                    log.debug("🚫 Dropping message from inactive handler");
                }
                return;
            }
            
            Segment segment = (Segment) msg;
            if (segment.getProtocol() == handshakeAgent.getProtocolId()) {
                // Validate channel before processing
                if (ctx.channel() != handshakeAgent.getChannel()) {
                    if (log.isDebugEnabled()) {
                        log.debug("🚫 Dropping handshake message from old channel");
                    }
                    return;
                }
                Message message = handshakeAgent.deserializeResponse(segment.getPayload());
                handshakeAgent.receiveResponse(message);
            } else {
                for (Agent agent : agents) {
                    if (!agent.isDone() && agent.getProtocolId() == segment.getProtocol()) {
                        // Validate channel before processing
                        if (ctx.channel() != agent.getChannel()) {
                            if (log.isDebugEnabled()) {
                                log.debug("🚫 Dropping message for protocol {} from old channel", agent.getProtocolId());
                            }
                            continue;
                        }
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
