package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbBlock;
import com.bloxbean.cardano.yaci.core.model.byron.ByronMainBlock;
import com.bloxbean.cardano.yaci.core.network.NodeClient;
import com.bloxbean.cardano.yaci.core.network.TCPNodeClient;
import com.bloxbean.cardano.yaci.core.network.UnixSocketNodeClient;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgent;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.n2c.LocalChainSyncAgent;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.n2c.LocalChainSyncAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.n2n.ChainSyncAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.n2n.ChainsyncAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2CVersionTableConstant;
import com.bloxbean.cardano.yaci.helper.api.Fetcher;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Consumer;

/**
 * Use this fetcher to fetch blocks from the current tip or from a wellknown point. The fetcher connects to a local
 * Cardano node through node socket file using node-to-client mini-protocol.
 * <p>Incoming blocks can be received by passing a {@link Consumer} function to the start method.</p>
 * <p>The following listeners can be added to receive various events from the agents</p>
 * <pre>
 * 1. {@link BlockfetchAgentListener} - To listen to events published by {@link BlockfetchAgent}
 * 2. {@link ChainSyncAgentListener} - To listen to events published by {@link ChainsyncAgent}
 * </pre>
 */
@Slf4j
public class N2CChainSyncFetcher implements Fetcher<Block> {
    private String nodeSocketFile;
    private VersionTable versionTable;
    private Point wellKnownPoint;
    private boolean syncFromLatest;
    private boolean tipFound = false;
    private HandshakeAgent handshakeAgent;
    private LocalChainSyncAgent chainSyncAgent;
    private NodeClient n2CClient;

    private String host;
    private int port;

    /**
     * Use this constructor to sync block from the tip
     *
     * @param nodeSocketFile path to local Cardano node's socket file
     * @param wellKnownPoint
     * @param protocolMagic
     */
    public N2CChainSyncFetcher(String nodeSocketFile, Point wellKnownPoint, long protocolMagic) {
        this(nodeSocketFile, wellKnownPoint, N2CVersionTableConstant.v1AndAbove(protocolMagic));
    }

    /**
     * Use this constructor to sync block from the wellknown point
     *
     * @param nodeSocketFile path to local Cardano node's socket file
     * @param wellKnownPoint
     * @param protocolMagic
     * @param syncFromLatest
     */
    public N2CChainSyncFetcher(String nodeSocketFile, Point wellKnownPoint, long protocolMagic, boolean syncFromLatest) {
        this(nodeSocketFile, wellKnownPoint, N2CVersionTableConstant.v1AndAbove(protocolMagic), syncFromLatest);
    }

    /**
     * Use this constructor to sync block from the tip.
     *
     * @param nodeSocketFile
     * @param versionTable
     * @param wellKnownPoint
     */
    public N2CChainSyncFetcher(String nodeSocketFile, Point wellKnownPoint, VersionTable versionTable) {
        this(nodeSocketFile, wellKnownPoint, versionTable, true);
    }

    /**
     * Use this constructor to sync blocks from the tip or from a well known point.
     *
     * @param nodeSocketFile path to local Cardano node's socket file
     * @param versionTable
     * @param wellKnownPoint
     * @param syncFromLatest true if sync from tip, false if sync from wellKnownPoint
     */
    public N2CChainSyncFetcher(String nodeSocketFile, Point wellKnownPoint, VersionTable versionTable, boolean syncFromLatest) {
        this.nodeSocketFile = nodeSocketFile;
        this.versionTable = versionTable;
        this.wellKnownPoint = wellKnownPoint;
        this.syncFromLatest = syncFromLatest;

        init();
    }

    /**
     * Use this constructor to sync blocks from the tip using host and port (node to client protocol)
     * To expose n2c protocol through TCP, a relay tool like socat can be used.
     *
     * @param host address to Cardano node node-to-client via tcp
     * @param port port to Cardano node node-to-client via tcp
     * @param wellKnownPoint
     * @param protocolMagic
     */
    public N2CChainSyncFetcher(String host, int port, Point wellKnownPoint, long protocolMagic) {
        this(host, port, wellKnownPoint, protocolMagic, true);
    }

    /**
     * Use this constructor to sync blocks from the tip or well known point using host and port (node to client protocol)
     * To expose n2c protocol through TCP, a relay tool like socat can be used.
     *
     * @param host address to Cardano node node-to-client via tcp
     * @param port port to Cardano node node-to-client via tcp
     * @param wellKnownPoint
     * @param protocolMagic
     * @param syncFromLatest true if sync from tip, false if sync from wellKnownPoint
     */
    public N2CChainSyncFetcher(String host, int port, Point wellKnownPoint, long protocolMagic, boolean syncFromLatest) {
        this(host, port, wellKnownPoint, N2CVersionTableConstant.v1AndAbove(protocolMagic), syncFromLatest);
    }

    /**
     * Use this constructor to sync blocks from the tip using host and port (node to client protocol)
     * To expose n2c protocol through TCP, a relay tool like socat can be used.
     *
     * @param host address to Cardano node node-to-client via tcp
     * @param port port to Cardano node node-to-client via tcp
     * @param wellKnownPoint
     * @param versionTable
     */
    public N2CChainSyncFetcher(String host, int port, Point wellKnownPoint, VersionTable versionTable) {
        this(host, port, wellKnownPoint, versionTable, true);
    }

    /**
     * Use this constructor to sync blocks from the tip or well known point using host and port (node to client protocol)
     * To expose n2c protocol through TCP, a relay tool like socat can be used.
     *
     * @param host address to Cardano node node-to-client via tcp
     * @param port port to Cardano node node-to-client via tcp
     * @param wellKnownPoint
     * @param versionTable
     * @param syncFromLatest true if sync from tip, false if sync from wellKnownPoint
     */
    public N2CChainSyncFetcher(String host, int port, Point wellKnownPoint, VersionTable versionTable, boolean syncFromLatest) {
        this.host = host;
        this.port = port;
        this.wellKnownPoint = wellKnownPoint;
        this.versionTable = versionTable;
        this.syncFromLatest = syncFromLatest;

        init();
    }

    private void init() {
        handshakeAgent = new HandshakeAgent(versionTable);
        chainSyncAgent = new LocalChainSyncAgent(new Point[]{wellKnownPoint});

        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                //start
                chainSyncAgent.sendNextMessage();
            }
        });

        chainSyncAgent.addListener(new LocalChainSyncAgentListener() {
            @Override
            public void intersactFound(Tip tip, Point point) {
                if (log.isDebugEnabled()) {
                    log.debug("Intersect found : Point : {},  Tip: {}", point, tip);
                    log.debug("Tip Found: {}", tipFound);
                }

                if (syncFromLatest) {
                    if (!tip.getPoint().equals(point) && !tipFound) {
                        chainSyncAgent.reset(tip.getPoint());
                        tipFound = true;
                    }
                } else {
                    if (!tipFound) {
                        chainSyncAgent.reset(point);
                        tipFound = true;
                    }
                }

                chainSyncAgent.sendNextMessage();
            }

            @Override
            public void intersactNotFound(Tip tip) {
                log.error("IntersactNotFound: {}", tip);
            }

            @Override
            public void rollforward(Tip tip, Block block) {
                long slot = block.getHeader().getHeaderBody().getSlot();
                String hash = block.getHeader().getHeaderBody().getBlockHash();

                if (log.isDebugEnabled()) {
                    log.debug("Rolled to slot: {}, block: {}", slot, block.getHeader().getHeaderBody().getBlockNumber());
                }

                if (log.isDebugEnabled())
                    log.debug("Trying to fetch block for {}", new Point(slot, hash));
                chainSyncAgent.sendNextMessage();
            }

            @Override
            public void rollforwardByronEra(Tip tip, ByronMainBlock byronMainBlock) {
                if (log.isDebugEnabled())
                    log.debug("ByronEraBlock : {}", byronMainBlock.getHeader().getConsensusData());
                chainSyncAgent.sendNextMessage();
            }

            @Override
            public void rollforwardByronEra(Tip tip, ByronEbBlock byronEbBlock) {
                if (log.isDebugEnabled())
                    log.debug("ByronEbBlock : {}", byronEbBlock.getHeader().getConsensusData());
                chainSyncAgent.sendNextMessage();
            }

            @Override
            public void rollbackward(Tip tip, Point toPoint) {
                chainSyncAgent.sendNextMessage();
            }
        });

        if (nodeSocketFile != null && !nodeSocketFile.isEmpty()) {
            n2CClient = new UnixSocketNodeClient(nodeSocketFile, handshakeAgent,
                    chainSyncAgent);
        } else if (host != null && !host.isEmpty())
            n2CClient = new TCPNodeClient(host, port, handshakeAgent, chainSyncAgent);
    }

    /**
     * Invoke this method or {@link #start()} method to start the sync process
     * @param consumer
     */
    @Override
    public void start(Consumer<Block> consumer) {
        chainSyncAgent.addListener(new LocalChainSyncAgentListener() {
            @Override
            public void rollforward(Tip tip, Block block) {
                if (consumer != null)
                    consumer.accept(block);
            }
        });

        n2CClient.start();
    }

    /**
     * Add a {@link LocalChainSyncAgentListener} to listen {@link LocalChainSyncAgent} events
     * @param listener
     */
    public void addChainSyncListener(LocalChainSyncAgentListener listener) {
        if (this.isRunning())
            throw new IllegalStateException("Listener can be added only before start() call");

        if (listener != null)
            chainSyncAgent.addListener(listener);
    }

    /**
     * Check if the connection is alive
     * @return
     */
    @Override
    public boolean isRunning() {
        return n2CClient.isRunning();
    }

    /**
     * Shutdown connection
     */
    @Override
    public void shutdown() {
        n2CClient.shutdown();
    }

}
