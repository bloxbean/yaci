package com.bloxbean.cardano.yaci.core.network.server;

import com.bloxbean.cardano.yaci.core.network.handlers.MiniProtoRequestDataEncoder;
import com.bloxbean.cardano.yaci.core.network.handlers.MiniProtoStreamingByteToMessageDecoder;
import com.bloxbean.cardano.yaci.core.network.server.handlers.MiniProtoServerInboundHandler;
import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.n2n.ChainSyncServerAgent;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockFetchServerAgent;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.TxSubmissionServerAgent;
import com.bloxbean.cardano.yaci.core.protocol.keepalive.KeepAliveServerAgent;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class NodeServerSession {
    private final Channel clientChannel;
    private final HandshakeAgent handshakeAgent;
    private final Agent[] agents;
    private final ChainState chainState;

    public NodeServerSession(Channel clientChannel, VersionTable versionTable, ChainState chainState) {
        this.clientChannel = clientChannel;
        this.handshakeAgent = new HandshakeAgent(versionTable, false);
        this.chainState = chainState;
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
        ChainSyncServerAgent chainSyncAgent = new ChainSyncServerAgent(chainState);
        BlockFetchServerAgent blockFetchAgent = new BlockFetchServerAgent(chainState);
        TxSubmissionServerAgent txSubmissionAgent = new TxSubmissionServerAgent();
        KeepAliveServerAgent keepAliveAgent = new KeepAliveServerAgent();

        // Set channels for agents
        chainSyncAgent.setChannel(clientChannel);
        blockFetchAgent.setChannel(clientChannel);
        txSubmissionAgent.setChannel(clientChannel);
        keepAliveAgent.setChannel(clientChannel);

        return new Agent[]{
            chainSyncAgent,
            blockFetchAgent,
            txSubmissionAgent,
            keepAliveAgent
        };
    }
}


