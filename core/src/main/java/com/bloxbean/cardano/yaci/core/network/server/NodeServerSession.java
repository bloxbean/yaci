package com.bloxbean.cardano.yaci.core.network.server;

import com.bloxbean.cardano.yaci.core.network.handlers.MiniProtoRequestDataEncoder;
import com.bloxbean.cardano.yaci.core.network.handlers.MiniProtoStreamingByteToMessageDecoder;
import com.bloxbean.cardano.yaci.core.network.server.handlers.MiniProtoServerInboundHandler;
import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class NodeServerSession {
    private final Channel clientChannel;
    private final HandshakeAgent handshakeAgent;
    private final Agent[] agents;

    public NodeServerSession(Channel clientChannel, VersionTable versionTable) {
        this.clientChannel = clientChannel;
        this.handshakeAgent = new HandshakeAgent(versionTable, false);
        this.agents = createAgents();

        setupPipeline();
    }

    private void setupPipeline() {
        ChannelPipeline pipeline = clientChannel.pipeline();
        pipeline.addLast(new MiniProtoRequestDataEncoder());
        pipeline.addLast(new MiniProtoStreamingByteToMessageDecoder(agents));
        pipeline.addLast(new MiniProtoServerInboundHandler(this));
    }

    public HandshakeAgent getHandshakeAgent() {
        return handshakeAgent;
    }

    public Agent[] getAgents() {
        return agents;
    }

    public Channel getClientChannel() {
        return clientChannel;
    }

    public void close() {
        log.info("Closing session for client: {}", clientChannel.remoteAddress());
        clientChannel.close();
    }

    private Agent[] createAgents() {
        // Initialize specific mini-protocol handlers (ChainSync, BlockFetch, etc.)
        return new Agent[]{

        };
    }
}


