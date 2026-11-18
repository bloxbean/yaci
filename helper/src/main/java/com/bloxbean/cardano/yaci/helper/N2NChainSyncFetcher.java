package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.core.exception.BlockParseRuntimeException;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.byron.ByronBlockHead;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbBlock;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbHead;
import com.bloxbean.cardano.yaci.core.model.byron.ByronMainBlock;
import com.bloxbean.cardano.yaci.core.network.TCPNodeClient;
import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgent;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.n2n.ChainSyncAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.n2n.ChainsyncAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import com.bloxbean.cardano.yaci.core.protocol.keepalive.KeepAliveAgent;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.LeiosFetchAgent;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.LeiosNotifyAgent;
import com.bloxbean.cardano.yaci.helper.api.Fetcher;
import com.bloxbean.cardano.yaci.core.common.GenesisConfig;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Use this fetcher to fetch blocks from the current tip or from a wellknown point. The fetcher connects to a remote
 * Cardano node through node host and port using node-to-node mini-protocol.
 * The block can be received by passing a {@link Consumer} to the start method.
 * The following listeners can be added to receive various events from the agents
 * 1. {@link BlockfetchAgentListener} - To listen to events published by {@link BlockfetchAgent}
 * 2. {@link ChainSyncAgentListener} - To listen to events published by {@link ChainsyncAgent}
 */
@Slf4j
public class N2NChainSyncFetcher implements Fetcher<Block> {
    private String host;
    private int port;
    private long protocolMagic;
    private VersionTable versionTable;
    private Point wellKnownPoint;
    private boolean syncFromLatest;
    private LeiosConfig leiosConfig;
    private boolean tipFound = false;
    private HandshakeAgent handshakeAgent;
    private KeepAliveAgent keepAliveAgent;
    private ChainsyncAgent chainSyncAgent;
    private BlockfetchAgent blockFetchAgent;
    private LeiosNotifyAgent leiosNotifyAgent;
    private LeiosFetchAgent leiosFetchAgent;
    private final List<LeiosSyncCoordinator> leiosSyncCoordinators = new ArrayList<>();
    private TCPNodeClient n2nClient;

    private int lastKeepAliveResponseCookie = 0;
    private long lastKeepAliveResponseTime = 0;

    /**
     * Construct {@link N2NChainSyncFetcher} to sync the blockchain
     *
     * @param host           Cardano node host
     * @param port           Cardano node port
     * @param wellKnownPoint a well known point
     * @param protocolMagic  protocol magic
     */
    public N2NChainSyncFetcher(String host, int port, Point wellKnownPoint, long protocolMagic) {
        this(host, port, wellKnownPoint, protocolMagic, true, LeiosConfig.defaultConfig());
    }

    public N2NChainSyncFetcher(String host, int port, Point wellKnownPoint, long protocolMagic,
                               LeiosConfig leiosConfig) {
        this(host, port, wellKnownPoint, protocolMagic, true, leiosConfig);
    }

    /**
     * Construct {@link N2NChainSyncFetcher} to sync the blockchain
     * @param host  Cardano node host
     * @param port  Cardano node port
     * @param wellKnownPoint point
     * @param protocolMagic protocol magic
     * @param syncFromLatest true if sync from latest block, false if sync from the well known point
     */
    public N2NChainSyncFetcher(String host, int port, Point wellKnownPoint, long protocolMagic, boolean syncFromLatest) {
        this(host, port, wellKnownPoint, protocolMagic, syncFromLatest, LeiosConfig.defaultConfig());
    }

    public N2NChainSyncFetcher(String host, int port, Point wellKnownPoint, long protocolMagic,
                               boolean syncFromLatest,
                               LeiosConfig leiosConfig) {
        this(host, port, wellKnownPoint, LeiosConfig.versionTableFor(protocolMagic, leiosConfig), syncFromLatest,
                protocolMagic, leiosConfig);
    }

    /**
     * Construct {@link N2NChainSyncFetcher} to sync the blockchain
     *
     * @param host           Cardano host
     * @param port           Cardano node port
     * @param wellKnownPoint a well known point
     * @param versionTable   N2N version table
     */
    public N2NChainSyncFetcher(String host, int port, Point wellKnownPoint, VersionTable versionTable) {
        this(host, port, wellKnownPoint, versionTable, true);
    }

    public N2NChainSyncFetcher(String host, int port, Point wellKnownPoint, VersionTable versionTable,
                               LeiosConfig leiosConfig) {
        this(host, port, wellKnownPoint, versionTable, true, LeiosConfig.protocolMagic(versionTable), leiosConfig);
    }

    /**
     * Construct {@link N2NChainSyncFetcher} to sync the blockchain
     *
     * @param host           Cardano node host
     * @param port           Cardano node port
     * @param wellKnownPoint a well known point
     * @param versionTable   N2N version table
     * @param syncFromLatest true if sync from latest block, false if sync from the well known point
     */
    public N2NChainSyncFetcher(String host, int port, Point wellKnownPoint, VersionTable versionTable, boolean syncFromLatest) {
        this(host, port, wellKnownPoint, versionTable, syncFromLatest, LeiosConfig.protocolMagic(versionTable),
                LeiosConfig.defaultConfig());
    }

    public N2NChainSyncFetcher(String host, int port, Point wellKnownPoint, VersionTable versionTable,
                               boolean syncFromLatest, LeiosConfig leiosConfig) {
        this(host, port, wellKnownPoint, versionTable, syncFromLatest, LeiosConfig.protocolMagic(versionTable),
                leiosConfig);
    }

    private N2NChainSyncFetcher(String host, int port, Point wellKnownPoint, VersionTable versionTable,
                                boolean syncFromLatest, long protocolMagic, LeiosConfig leiosConfig) {
        this.host = host;
        this.port = port;
        this.versionTable = versionTable;
        this.protocolMagic = protocolMagic;
        this.wellKnownPoint = wellKnownPoint;
        this.syncFromLatest = syncFromLatest;
        this.leiosConfig = leiosConfig != null ? leiosConfig : LeiosConfig.defaultConfig();

        init();
    }

    private void init() {
        handshakeAgent = new HandshakeAgent(versionTable);
        keepAliveAgent = new KeepAliveAgent();
        chainSyncAgent = new ChainsyncAgent(new Point[]{wellKnownPoint});
        blockFetchAgent = new BlockfetchAgent();
        blockFetchAgent.resetPoints(wellKnownPoint, wellKnownPoint);
        if (leiosConfig.shouldAttach(protocolMagic)) {
            leiosNotifyAgent = new LeiosNotifyAgent();
            leiosFetchAgent = new LeiosFetchAgent();
        }

        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                keepAliveAgent.sendKeepAlive(1234);
                //start
                chainSyncAgent.sendNextMessage();
                startLeiosIfCompatible();
            }
        });

        chainSyncAgent.addListener(new ChainSyncAgentListener() {
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
            public void rollforward(Tip tip, BlockHeader blockHeader) {
                long slot = blockHeader.getHeaderBody().getSlot();
                String hash = blockHeader.getHeaderBody().getBlockHash();

                resetBlockFetchAgentAndFetchBlock(slot, hash);
            }

            @Override
            public void rollforwardByronEra(Tip tip, ByronBlockHead byronHead) {
                long absoluteSlot = byronHead.getConsensusData().getAbsoluteSlot();
                String hash = byronHead.getBlockHash();
                resetBlockFetchAgentAndFetchBlock(absoluteSlot, hash);
            }

            @Override
            public void rollforwardByronEra(Tip tip, ByronEbHead byronEbHead) {
                long epoch = byronEbHead.getConsensusData().getEpoch();
                String hash = byronEbHead.getBlockHash();
                resetBlockFetchAgentAndFetchBlock(byronEbHead.getConsensusData().getAbsoluteSlot(), hash);
            }

            @Override
            public void rollbackward(Tip tip, Point toPoint) {
                chainSyncAgent.sendNextMessage();
            }
        });

        blockFetchAgent.addListener(new BlockfetchAgentListener() {
            @Override
            public void blockFound(Block block) {
                if (log.isDebugEnabled()) {
                    log.debug("Block Found >> " + block);
                }

                Point fetchedPoint = new Point(
                    block.getHeader().getHeaderBody().getSlot(),
                    block.getHeader().getHeaderBody().getBlockHash()
                );
                chainSyncAgent.confirmBlock(fetchedPoint);

                chainSyncAgent.sendNextMessage();
            }

            @Override
            public void byronBlockFound(ByronMainBlock byronBlock) {
                long absoluteSlot = GenesisConfig.getInstance().absoluteSlot(Era.Byron,
                        byronBlock.getHeader().getConsensusData().getSlotId().getEpoch(),
                        byronBlock.getHeader().getConsensusData().getSlotId().getSlot());

                Point fetchedPoint = new Point(
                    absoluteSlot,
                    byronBlock.getHeader().getBlockHash()
                );

                chainSyncAgent.confirmBlock(fetchedPoint);

                chainSyncAgent.sendNextMessage();
            }

            @Override
            public void byronEbBlockFound(ByronEbBlock byronEbBlock) {
                long absoluteSlot = GenesisConfig.getInstance().absoluteSlot(
                    Era.Byron,
                    byronEbBlock.getHeader().getConsensusData().getEpoch(),
                    0
                );
                Point fetchedPoint = new Point(
                    absoluteSlot,
                    byronEbBlock.getHeader().getBlockHash()
                );
                chainSyncAgent.confirmBlock(fetchedPoint);

                chainSyncAgent.sendNextMessage();
            }

            @Override
            public void onParsingError(BlockParseRuntimeException e) {
                chainSyncAgent.sendNextMessage();
            }
        });

        keepAliveAgent.addListener(response -> {
            lastKeepAliveResponseCookie = response.getCookie();
            lastKeepAliveResponseTime = System.currentTimeMillis();
        });

        List<Agent> agents = new ArrayList<>();
        agents.add(keepAliveAgent);
        agents.add(chainSyncAgent);
        agents.add(blockFetchAgent);
        if (leiosNotifyAgent != null && leiosFetchAgent != null) {
            agents.add(leiosNotifyAgent);
            agents.add(leiosFetchAgent);
        }
        n2nClient = new TCPNodeClient(host, port, handshakeAgent, agents.toArray(new Agent[0]));
    }

    /**
     * Registers the same listener for helper-level Leios callbacks when this connection attaches Leios agents.
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

    private void resetBlockFetchAgentAndFetchBlock(long slot, String hash) {
        if (log.isDebugEnabled()) {
            log.debug("Rolled to slot: {}, block: {}", slot, hash);
        }

        blockFetchAgent.resetPoints(new Point(slot, hash), new Point(slot, hash));

        if (log.isDebugEnabled())
            log.debug("Trying to fetch block for {}", new Point(slot, hash));
        blockFetchAgent.sendNextMessage();
    }

    /**
     * Invoke this method or {@link #start()} method to start the sync process
     *
     * @param consumer
     */
    @Override
    public void start(Consumer<Block> consumer) {
        blockFetchAgent.addListener(new BlockfetchAgentListener() {
            @Override
            public void blockFound(Block block) {
                if (consumer != null)
                    consumer.accept(block);
            }
        });

        n2nClient.start();
    }

    /**
     * Add a {@link BlockfetchAgentListener} to listen to {@link BlockfetchAgent} events
     *
     * @param listener
     */
    public void addBlockFetchListener(BlockfetchAgentListener listener) {
        if (this.isRunning())
            throw new IllegalStateException("Listener can be added only before start() call");

        if (listener != null)
            blockFetchAgent.addListener(listener);
    }

    /**
     * Add a {@link ChainSyncAgentListener} to listen to {@link ChainsyncAgent} events
     *
     * @param listener
     */
    public void addChainSyncListener(ChainSyncAgentListener listener) {
        if (this.isRunning())
            throw new IllegalStateException("Listener can be added only before start() call");

        if (listener != null)
            chainSyncAgent.addListener(listener);
    }

    /**
     * Send keep alive message
     * @param cookie
     */
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

    /**
     * Check if the connection is alive
     *
     * @return
     */
    @Override
    public boolean isRunning() {
        return n2nClient.isRunning();
    }

    /**
     * Shutdown connection
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

    public static void main(String[] args) throws Exception {
        N2NChainSyncFetcher chainSyncFetcher = new N2NChainSyncFetcher("localhost", 30000, Constants.WELL_KNOWN_PREPROD_POINT, Constants.PREPROD_PROTOCOL_MAGIC);

        chainSyncFetcher.addChainSyncListener(new ChainSyncAgentListener() {
            @Override
            public void rollforward(Tip tip, BlockHeader blockHeader) {
                log.info("RollForward !!!");
            }
        });

        chainSyncFetcher.start(block -> {
            log.info(">>>> Block >>>> " + block.getHeader().getHeaderBody().getBlockNumber());
        });
    }
}
