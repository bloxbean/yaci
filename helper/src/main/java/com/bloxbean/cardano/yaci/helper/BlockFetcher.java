package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.network.TCPNodeClient;
import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgent;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import com.bloxbean.cardano.yaci.core.protocol.keepalive.KeepAliveAgent;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.LeiosFetchAgent;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.LeiosNotifyAgent;
import com.bloxbean.cardano.yaci.helper.api.Fetcher;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Use this fetcher to fetch blocks from Point-1 to Point-2 from a remote Cardano node using Node-to-node protocol.
 * There are two ways to receive blocks fetched by this fetcher
 * <p>
 * 1. through a consumer function passed to {@link #start(Consumer)},
 * <br>
 * 2. by attaching a {@link BlockfetchAgentListener} through {@link #addBlockFetchListener(BlockfetchAgentListener)}
 * </p>
 * <br>
 * <b>Note:</b> This fetcher can only return Shelley / Post-Shelley era blocks for now
 * <p></p>
 * <p>
 * Example: Fetch block from point-1 to point-2 and receive blocks in a {@link Consumer} function
 * </p>
 *
 * <pre>
 * {@code
 * BlockFetcher blockFetcher = new BlockFetcher(node, nodePort, Constants.MAINNET_PROTOCOL_MAGIC);
 * blockFetcher.start(block -> {
 *    //process block
 * });
 *
 * Point from = new Point(70895877, "094de3242c9cc6504851c9ca1f109c379840364bb6a1a941353c87cf1f22cf06");
 * Point to = new Point(70896002, "1f58983e784ff3eabd9bdb97808402086baffbf51742a120d3635df867c16ad9");
 * blockFetcher.fetch(from, to);
 *
 * blockFetcher.shutdown();
 *  }
 * </pre>
 */
@Slf4j
public class BlockFetcher implements Fetcher<Block> {
    private String host;
    private int port;
    private long protocolMagic;
    private VersionTable versionTable;
    private LeiosConfig leiosConfig;
    private HandshakeAgent handshakeAgent;
    private KeepAliveAgent keepAliveAgent;
    private BlockfetchAgent blockfetchAgent;
    private LeiosNotifyAgent leiosNotifyAgent;
    private LeiosFetchAgent leiosFetchAgent;
    private final List<LeiosSyncCoordinator> leiosSyncCoordinators = new ArrayList<>();
    private TCPNodeClient n2nClient;

    private int lastKeepAliveResponseCookie = 0;
    private long lastKeepAliveResponseTime = 0;

    /**
     * Constructor to create BlockFetcher instance
     * @param host Cardano node host
     * @param port Cardano node port
     * @param protocolMagic Protocol Magic
     */
    public BlockFetcher(String host, int port, long protocolMagic) {
        this(host, port, protocolMagic, LeiosConfig.defaultConfig());
    }

    /**
     * Construct a block fetcher with optional Leios integration.
     * In {@link LeiosConfig.Mode#AUTO}, range-oriented fetchers do not attach Leios agents; use
     * {@link LeiosConfig.Mode#ENABLED} when near-tip Endorser Block events are desired on this connection.
     */
    public BlockFetcher(String host, int port, long protocolMagic, LeiosConfig leiosConfig) {
        this(host, port, LeiosConfig.versionTableForRange(protocolMagic, leiosConfig), protocolMagic, leiosConfig);
    }

    /**
     * Constructor to create BlockFetcher instance
     * @param host Cardano node host
     * @param port Cardano node port
     * @param versionTable VersionTable for N2N protocol
     */
    public BlockFetcher(String host, int port, VersionTable versionTable) {
        this(host, port, versionTable, LeiosConfig.defaultConfig());
    }

    /**
     * Construct a block fetcher with a caller-provided node-to-node version table and Leios policy.
     */
    public BlockFetcher(String host, int port, VersionTable versionTable, LeiosConfig leiosConfig) {
        this(host, port, versionTable, LeiosConfig.protocolMagic(versionTable), leiosConfig);
    }

    private BlockFetcher(String host, int port, VersionTable versionTable, long protocolMagic,
                         LeiosConfig leiosConfig) {
        this.host = host;
        this.port = port;
        this.versionTable = versionTable;
        this.protocolMagic = protocolMagic;
        this.leiosConfig = leiosConfig != null ? leiosConfig : LeiosConfig.defaultConfig();
        init();
    }

    private void init() {
        handshakeAgent = new HandshakeAgent(versionTable);
        keepAliveAgent = new KeepAliveAgent();
        blockfetchAgent = new BlockfetchAgent();
        if (leiosConfig.shouldAttachForRange(protocolMagic)) {
            leiosNotifyAgent = new LeiosNotifyAgent();
            leiosFetchAgent = new LeiosFetchAgent();
        }

        List<Agent> agents = new ArrayList<>();
        agents.add(keepAliveAgent);
        agents.add(blockfetchAgent);
        if (leiosNotifyAgent != null && leiosFetchAgent != null) {
            agents.add(leiosNotifyAgent);
            agents.add(leiosFetchAgent);
        }
        n2nClient = new TCPNodeClient(host, port, handshakeAgent, agents.toArray(new Agent[0]));

        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                blockfetchAgent.sendNextMessage();
                keepAliveAgent.sendKeepAlive(1234);
                startLeiosIfCompatible();
            }
        });

        keepAliveAgent.addListener(response -> {
            lastKeepAliveResponseCookie = response.getCookie();
            lastKeepAliveResponseTime = System.currentTimeMillis();
        });
    }

    /**
     * Invoke this method to establish the connection with remote Cardano node.
     * This method should be called only once.
     * @param receiver Consumer function to receive {@link Block}
     */
    public void start(Consumer<Block> receiver) {
        blockfetchAgent.addListener(new BlockfetchAgentListener() {
            @Override
            public void blockFound(Block block) {
                if (receiver != null)
                    receiver.accept(block);
            }
        });

        if (!n2nClient.isRunning())
            n2nClient.start();
    }

    /**
     * Invoke this method to fetch blocks
     * This method should be called after {@link #start(Consumer)}
     * @param from Start point
     * @param to End point
     */
    public void fetch(Point from, Point to) {
        if (!n2nClient.isRunning())
            throw new IllegalStateException("fetch() should be called after start()");

        blockfetchAgent.resetPoints(from, to);
        if (!blockfetchAgent.isDone())
            blockfetchAgent.sendNextMessage();
        else
            log.warn("Agent status is Done. Can't reschedule new points.");
    }

    /**
     * Add a {@link BlockfetchAgentListener} to listen different block fetch events from {@link BlockfetchAgent}
     * @param listener
     */
    public void addBlockFetchListener(BlockfetchAgentListener listener) {
        if (this.isRunning())
            throw new IllegalStateException("Listener can be added only before start() call");

        if (listener != null)
            blockfetchAgent.addListener(listener);
    }

    /**
     * Registers the same listener for Leios Endorser Block and vote callbacks when Leios agents are attached.
     */
    public void addLeiosDataListener(BlockChainDataListener listener) {
        if (this.isRunning())
            throw new IllegalStateException("Listener can be added only before start() call");

        if (listener == null || leiosNotifyAgent == null || leiosFetchAgent == null)
            return;

        LeiosSyncCoordinator coordinator = new LeiosSyncCoordinator(listener, leiosFetchAgent, leiosConfig);
        leiosSyncCoordinators.add(coordinator);
        leiosNotifyAgent.addListener(coordinator);
        leiosFetchAgent.addListener(coordinator);
    }

    /**
     * Check if the agent connection is still alive
     * @return true if alive, false if not
     */
    @Override
    public boolean isRunning() {
        return n2nClient.isRunning();
    }

    /**
     * Invoke this method to close the socket connection to node
     */
    @Override
    public void shutdown() {
        for (LeiosSyncCoordinator coordinator : leiosSyncCoordinators) {
            coordinator.close();
        }
        if (leiosNotifyAgent != null) {
            leiosNotifyAgent.shutdown();
        }
        if (leiosFetchAgent != null) {
            leiosFetchAgent.done();
        }
        n2nClient.shutdown();
    }

    public void sendKeepAliveMessage(int cookie) {
        if (n2nClient.isRunning())
            keepAliveAgent.sendKeepAlive(cookie);
    }

    /**
     * Get the last keep alive response cookie
     * @return
     */
    public int getLastKeepAliveResponseCookie() {
        return lastKeepAliveResponseCookie;
    }

    /**
     * Get the last keep alive response time
     * @return
     */
    public long getLastKeepAliveResponseTime() {
        return lastKeepAliveResponseTime;
    }

    private void startLeiosIfCompatible() {
        if (leiosNotifyAgent == null || leiosFetchAgent == null) {
            return;
        }

        if (leiosConfig.isCompatible(handshakeAgent.getProtocolVersion(), protocolMagic)) {
            leiosNotifyAgent.start();
        } else {
            leiosNotifyAgent.stopAutoRequestNext();
        }
    }

}
