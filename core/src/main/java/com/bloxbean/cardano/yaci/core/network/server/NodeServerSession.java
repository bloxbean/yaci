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
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.TxSubmissionListener;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.TxSubmissionConfig;
import com.bloxbean.cardano.yaci.core.protocol.keepalive.KeepAliveServerAgent;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
public class NodeServerSession {
    private final Channel clientChannel;
    private final HandshakeAgent handshakeAgent;
    private final Agent[] agents;
    private final ChainState chainState;
    private final TxSubmissionListener txSubmissionListener;
    private final TxSubmissionConfig txSubmissionConfig;
    private final List<AgentFactory> agentFactories;

    public NodeServerSession(Channel clientChannel, VersionTable versionTable, ChainState chainState,
                           TxSubmissionListener txSubmissionListener, TxSubmissionConfig txSubmissionConfig) {
        this(clientChannel, versionTable, chainState, txSubmissionListener, txSubmissionConfig, Collections.emptyList());
    }

    public NodeServerSession(Channel clientChannel, VersionTable versionTable, ChainState chainState,
                           TxSubmissionListener txSubmissionListener, TxSubmissionConfig txSubmissionConfig,
                           List<AgentFactory> agentFactories) {
        this.clientChannel = clientChannel;
        this.handshakeAgent = new HandshakeAgent(versionTable, false);
        this.chainState = chainState;
        this.txSubmissionListener = txSubmissionListener;
        this.txSubmissionConfig = txSubmissionConfig != null ? txSubmissionConfig : TxSubmissionConfig.createDefault();
        this.agentFactories = agentFactories != null ? agentFactories : Collections.emptyList();
        this.agents = createAgents();

        setupPipeline();
    }

    private void setupPipeline() {
        ChannelPipeline pipeline = clientChannel.pipeline();
        // Add idle state detection - 10 minutes of inactivity triggers disconnect
        pipeline.addLast(new IdleStateHandler(0, 0, 600));
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

    public void close() {
        log.info("Closing session for client: {}", clientChannel.remoteAddress());
        clientChannel.close();
    }

    private Agent[] createAgents() {
        // Initialize L1 mini-protocol handlers
        ChainSyncServerAgent chainSyncAgent = new ChainSyncServerAgent(chainState);
        BlockFetchServerAgent blockFetchAgent = new BlockFetchServerAgent(chainState);
        TxSubmissionServerAgent txSubmissionAgent = new TxSubmissionServerAgent(txSubmissionConfig);
        KeepAliveServerAgent keepAliveAgent = new KeepAliveServerAgent();

        // Set channels for L1 agents
        chainSyncAgent.setChannel(clientChannel);
        blockFetchAgent.setChannel(clientChannel);
        txSubmissionAgent.setChannel(clientChannel);
        keepAliveAgent.setChannel(clientChannel);

        // Register TxSubmissionListener if provided
        if (txSubmissionListener != null) {
            txSubmissionAgent.addListener(txSubmissionListener);
        }

        List<Agent<?>> allAgents = new ArrayList<>();
        allAgents.add(chainSyncAgent);
        allAgents.add(blockFetchAgent);
        allAgents.add(txSubmissionAgent);
        allAgents.add(keepAliveAgent);

        // Create and add app-layer agents from factories
        for (AgentFactory factory : agentFactories) {
            try {
                Agent<?> agent = factory.createAgent();
                if (agent != null) {
                    agent.setChannel(clientChannel);
                    allAgents.add(agent);
                    log.info("Added app-layer agent: {} (protocol {})",
                            agent.getClass().getSimpleName(), agent.getProtocolId());
                }
            } catch (Exception e) {
                log.warn("Failed to create agent from factory: {}", e.getMessage());
            }
        }

        return allAgents.toArray(new Agent[0]);
    }
}


