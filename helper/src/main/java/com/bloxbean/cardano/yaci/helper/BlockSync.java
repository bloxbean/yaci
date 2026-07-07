package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import com.bloxbean.cardano.yaci.helper.listener.BlockFetchAgentListenerAdapter;
import com.bloxbean.cardano.yaci.helper.listener.ChainSyncListenerAdapter;

/**
 * A high level helper class to sync blockchain data from tip or from a particular point using node-to-node miniprotocol
 * and receive in a {@link BlockChainDataListener} instance.
 */
public class BlockSync {
    private String host;
    private int port;
    private Long protocolMagic;
    private Point wellKnownPoint;
    private VersionTable versionTable;
    private LeiosConfig leiosConfig = LeiosConfig.defaultConfig();

    private N2NChainSyncFetcher n2NChainSyncFetcher;

    /**
     * Construct a BlockSync instance
     * @param host Cardano node host
     * @param port Cardano node port
     * @param protocolMagic Protocol magic
     * @param wellKnownPoint A wellknown point
     */
    public BlockSync(String host, int port, long protocolMagic, Point wellKnownPoint) {
        this(host, port, protocolMagic, wellKnownPoint, LeiosConfig.defaultConfig());
    }

    /**
     * Construct a tip-following block sync with optional Leios integration.
     * In {@link LeiosConfig.Mode#AUTO}, Leios agents attach only for the Musashi network magic.
     */
    public BlockSync(String host, int port, long protocolMagic, Point wellKnownPoint, LeiosConfig leiosConfig) {
        this.host = host;
        this.port = port;
        this.protocolMagic = protocolMagic;
        this.wellKnownPoint = wellKnownPoint;
        this.leiosConfig = leiosConfig != null ? leiosConfig : LeiosConfig.defaultConfig();
    }

    /**
     * Construct a BlockSync instance
     * @param host Cardano node host
     * @param port Cardano node port
     * @param wellKnownPoint A wellknown point
     * @param versionTable {@link VersionTable} instance
     */
    public BlockSync(String host, int port, Point wellKnownPoint, VersionTable versionTable) {
        this(host, port, wellKnownPoint, versionTable, LeiosConfig.defaultConfig());
    }

    /**
     * Construct a tip-following block sync with a caller-provided version table and Leios policy.
     */
    public BlockSync(String host, int port, Point wellKnownPoint, VersionTable versionTable,
                     LeiosConfig leiosConfig) {
        this.host = host;
        this.port = port;
        this.wellKnownPoint = wellKnownPoint;
        this.versionTable = versionTable;
        this.protocolMagic = LeiosConfig.protocolMagic(versionTable);
        this.leiosConfig = leiosConfig != null ? leiosConfig : LeiosConfig.defaultConfig();
    }

    /**
     * Start sync from a given point
     * @param point point to start sync from
     * @param blockChainDataListener {@link BlockChainDataListener} instance
     */
    public void startSync(Point point, BlockChainDataListener blockChainDataListener) {
        if (n2NChainSyncFetcher != null && n2NChainSyncFetcher.isRunning())
            n2NChainSyncFetcher.shutdown();

        initializeAgentAndStart(point, blockChainDataListener, false);
    }

    private void initializeAgentAndStart(Point point, BlockChainDataListener blockChainDataListener, boolean syncFromTip) {
        if (versionTable != null) {
            n2NChainSyncFetcher = new N2NChainSyncFetcher(host, port, point, versionTable, syncFromTip, leiosConfig);
        } else {
            n2NChainSyncFetcher = new N2NChainSyncFetcher(host, port, point, protocolMagic, syncFromTip, leiosConfig);
        }

        BlockFetchAgentListenerAdapter blockfetchAgentListener = new BlockFetchAgentListenerAdapter(blockChainDataListener);
        ChainSyncListenerAdapter chainSyncAgentListener = new ChainSyncListenerAdapter(blockChainDataListener);
        n2NChainSyncFetcher.addChainSyncListener(chainSyncAgentListener);
        n2NChainSyncFetcher.addBlockFetchListener(blockfetchAgentListener);
        n2NChainSyncFetcher.addLeiosDataListener(blockChainDataListener);

        n2NChainSyncFetcher.start();
    }

    /**
     * Start sync from tip
     * @param blockChainDataListener {@link BlockChainDataListener} instance
     */
    public void startSyncFromTip(BlockChainDataListener blockChainDataListener) {

        if (n2NChainSyncFetcher != null && n2NChainSyncFetcher.isRunning())
            n2NChainSyncFetcher.shutdown();

        initializeAgentAndStart(wellKnownPoint, blockChainDataListener, true);
    }

    /**
     * Send keep alive message
     * @param cookie
     */
    public void sendKeepAliveMessage(int cookie) {
        if (n2NChainSyncFetcher.isRunning())
            n2NChainSyncFetcher.sendKeepAliveMessage(cookie);
    }

    /**
     * Get the last keep alive response cookie
     * @return
     */
    public int getLastKeepAliveResponseCookie() {
        return n2NChainSyncFetcher.getLastKeepAliveResponseCookie();
    }

    /**
     * Get the last keep alive response time
     * @return
     */
    public long getLastKeepAliveResponseTime() {
        return n2NChainSyncFetcher.getLastKeepAliveResponseTime();
    }

    /**
     * Stop the fetcher
     */
    public void stop() {
        n2NChainSyncFetcher.shutdown();
    }

    /**
     * Check if the connection is alive
     */
    public boolean isRunning() {
        return n2NChainSyncFetcher.isRunning();
    }
}
