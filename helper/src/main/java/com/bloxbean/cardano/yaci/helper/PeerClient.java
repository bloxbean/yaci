package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.common.TxBodyType;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.n2n.ChainSyncAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.TxSubmissionListener;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import com.bloxbean.cardano.yaci.helper.listener.BlockFetchAgentListenerAdapter;
import com.bloxbean.cardano.yaci.helper.listener.ChainSyncListenerAdapter;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.function.Predicate;

/**
 * A high level helper class to sync blockchain data from tip or from a particular point using node-to-node miniprotocol
 * and receive in a {@link BlockChainDataListener} instance.
 *
 * Enhanced with pipelining support for high-performance synchronization:
 * - Simple applications: Use existing methods for automatic header+body sync
 * - Node implementations: Use new pipelining methods for independent control
 * - Performance optimization: Configure pipelining strategies and parameters
 */
@Slf4j
public class PeerClient {
    private String host;
    private int port;
    private Point wellKnownPoint;
    private VersionTable versionTable;

    private N2NPeerFetcher n2NPeerFetcher;

    /**
     * Construct a BlockSync instance
     * @param host Cardano node host
     * @param port Cardano node port
     * @param protocolMagic Protocol magic
     * @param wellKnownPoint A wellknown point
     */
    public PeerClient(String host, int port, long protocolMagic, Point wellKnownPoint) {
        this(host, port, wellKnownPoint, N2NVersionTableConstant.v4AndAbove(protocolMagic));
    }

    /**
     * Construct a BlockSync instance
     * @param host Cardano node host
     * @param port Cardano node port
     * @param wellKnownPoint A wellknown point
     * @param versionTable {@link VersionTable} instance
     */
    public PeerClient(String host, int port, Point wellKnownPoint, VersionTable versionTable) {
        this.host = host;
        this.port = port;
        this.wellKnownPoint = wellKnownPoint;
        this.versionTable = versionTable;
    }

    public void connect(BlockChainDataListener blockChainDataListener, TxSubmissionListener txSubmissionListener) {
        if (n2NPeerFetcher != null && n2NPeerFetcher.isRunning())
            throw new IllegalStateException("Already connected. Please call shutdown() before connecting again.");

        n2NPeerFetcher = new N2NPeerFetcher(host, port, wellKnownPoint, versionTable);

        BlockFetchAgentListenerAdapter blockfetchAgentListener = new BlockFetchAgentListenerAdapter(blockChainDataListener);
        ChainSyncListenerAdapter chainSyncAgentListener = new ChainSyncListenerAdapter(blockChainDataListener);

        n2NPeerFetcher.addChainSyncListener(chainSyncAgentListener);
        n2NPeerFetcher.addBlockFetchListener(blockfetchAgentListener);
        n2NPeerFetcher.addTxSubmissionListener(txSubmissionListener);

        // Add a keep alive thread listener to send periodic keep alive messages
        n2NPeerFetcher.addChainSyncListener(new ChainSyncListenerAdapter(new KeepAliveThreadListener()));

        n2NPeerFetcher.start();
    }

    /**
     * Start sync from a given point
     * @param point point to start sync from
     * @param blockChainDataListener {@link BlockChainDataListener} instance
     */
    public void startSync(Point point, BlockChainDataListener blockChainDataListener, TxSubmissionListener txSubmissionListener) {
        if (n2NPeerFetcher != null && n2NPeerFetcher.isRunning())
            n2NPeerFetcher.shutdown();

        initializeAgentAndStart(point, blockChainDataListener, txSubmissionListener);
    }

    private void initializeAgentAndStart(Point point, BlockChainDataListener blockChainDataListener,
                                         TxSubmissionListener txSubmissionListener) {
        n2NPeerFetcher = new N2NPeerFetcher(host, port, point, versionTable);

        BlockFetchAgentListenerAdapter blockfetchAgentListener = new BlockFetchAgentListenerAdapter(blockChainDataListener);
        ChainSyncListenerAdapter chainSyncAgentListener = new ChainSyncListenerAdapter(blockChainDataListener);
        n2NPeerFetcher.addChainSyncListener(chainSyncAgentListener);
        n2NPeerFetcher.addBlockFetchListener(blockfetchAgentListener);
        n2NPeerFetcher.addTxSubmissionListener(txSubmissionListener);

        n2NPeerFetcher.start();
    }

    public void fetch(Point from, Point to) {
        n2NPeerFetcher.fetch(from, to);
    }

    public void startSync(Point from) {
        n2NPeerFetcher.startSync(from);
    }

    /**
     * Start sync from tip
     * @param blockChainDataListener {@link BlockChainDataListener} instance
     */
    public void startSyncFromTip(BlockChainDataListener blockChainDataListener, TxSubmissionListener txSubmissionListener) {

        if (n2NPeerFetcher != null && n2NPeerFetcher.isRunning())
            n2NPeerFetcher.shutdown();

        initializeAgentAndStart(wellKnownPoint, blockChainDataListener, txSubmissionListener);
    }


    /**
     * Send keep alive message
     * @param cookie
     */
    public void sendKeepAliveMessage(int cookie) {
        if (n2NPeerFetcher.isRunning())
            n2NPeerFetcher.sendKeepAliveMessage(cookie);
    }

    /**
     * Get the last keep alive response cookie
     * @return
     */
    public int getLastKeepAliveResponseCookie() {
        return n2NPeerFetcher.getLastKeepAliveResponseCookie();
    }

    /**
     * Get the last keep alive response time
     * @return
     */
    public long getLastKeepAliveResponseTime() {
        return n2NPeerFetcher.getLastKeepAliveResponseTime();
    }

    /**
     * Stop the fetcher
     */
    public void stop() {
        n2NPeerFetcher.shutdown();
    }

    /**
     * Check if the connection is alive
     */
    public boolean isRunning() {
        return n2NPeerFetcher.isRunning();
    }

    public void addTxSubmissionListener(TxSubmissionListener txSubmissionListener) {
        if (this.isRunning())
            throw new IllegalStateException("Listener can be added only before start() call");

        if (txSubmissionListener != null)
            n2NPeerFetcher.addTxSubmissionListener(txSubmissionListener);
    }

    public void submitTxBytes(String txHash, byte[] txBytes, TxBodyType txBodyType) {
        n2NPeerFetcher.submitTxBytes(txHash, txBytes, txBodyType);
    }

    public void enableTxSubmission() {
        n2NPeerFetcher.enableTxSubmission();
    }

    // ========================================
    // NEW PIPELINING METHODS
    // ========================================

    /**
     * Start ChainSync only - headers will be received but no bodies fetched
     * Useful for header-only synchronization or when you want to control body fetching manually
     *
     * @param from Point to start sync from
     * @param chainSyncListener Listener for chain sync events
     */
    public void startChainSyncOnly(Point from, ChainSyncAgentListener chainSyncListener) {
        if (n2NPeerFetcher != null && n2NPeerFetcher.isRunning())
            n2NPeerFetcher.shutdown();

        n2NPeerFetcher = new N2NPeerFetcher(host, port, wellKnownPoint, versionTable);

        if (chainSyncListener != null) {
            n2NPeerFetcher.addChainSyncListener(chainSyncListener);
        }

        n2NPeerFetcher.startChainSyncOnly(from);
    }

    /**
     * Start BlockFetch only - fetch block bodies for specified range
     * Must be called after connection is established
     *
     * @param from Starting point for body fetch
     * @param to Ending point for body fetch
     * @param blockChainDataListener Listener for block events
     */
    public void startBlockFetchOnly(Point from, Point to, BlockChainDataListener blockChainDataListener) {
        if (n2NPeerFetcher == null || !n2NPeerFetcher.isRunning()) {
            throw new IllegalStateException("Connection not established. Call connect() first.");
        }

        if (blockChainDataListener != null) {
            BlockFetchAgentListenerAdapter blockfetchAgentListener = new BlockFetchAgentListenerAdapter(blockChainDataListener);
            n2NPeerFetcher.addBlockFetchListener(blockfetchAgentListener);
        }

        n2NPeerFetcher.startBlockFetchOnly(from, to);
    }

    /**
     * Start pipelined sync with full parallelization
     * Headers and bodies are processed independently with configurable pipelining
     *
     * @param from Point to start sync from
     * @param config Pipeline configuration
     * @param blockChainDataListener Listener for block events
     * @param chainSyncListener Listener for chain sync events (optional)
     * @param txSubmissionListener Listener for tx submission events (optional)
     */
    public void startPipelinedSync(Point from, PipelineConfig config,
                                 BlockChainDataListener blockChainDataListener,
                                 ChainSyncAgentListener chainSyncListener,
                                 TxSubmissionListener txSubmissionListener) {
        if (n2NPeerFetcher != null && n2NPeerFetcher.isRunning())
            n2NPeerFetcher.shutdown();

        n2NPeerFetcher = new N2NPeerFetcher(host, port, wellKnownPoint, versionTable);

        // Set up listeners
        if (blockChainDataListener != null) {
            BlockFetchAgentListenerAdapter blockfetchAgentListener = new BlockFetchAgentListenerAdapter(blockChainDataListener);
            ChainSyncListenerAdapter chainSyncAgentListener = new ChainSyncListenerAdapter(blockChainDataListener);
            n2NPeerFetcher.addBlockFetchListener(blockfetchAgentListener);
            n2NPeerFetcher.addChainSyncListener(chainSyncAgentListener);
        }

        if (chainSyncListener != null) {
            n2NPeerFetcher.addChainSyncListener(chainSyncListener);
        }

        if (txSubmissionListener != null) {
            n2NPeerFetcher.addTxSubmissionListener(txSubmissionListener);
        }

        n2NPeerFetcher.startPipelinedSync(from, config);
    }

    /**
     * Start pipelined sync with default high-performance configuration
     *
     * @param from Point to start sync from
     * @param blockChainDataListener Listener for block events
     */
    public void startPipelinedSync(Point from, BlockChainDataListener blockChainDataListener) {
        startPipelinedSync(from, PipelineConfig.highPerformanceNodeConfig(),
                         blockChainDataListener, null, null);
    }

    /**
     * Start pipelined sync for node implementations with custom configuration
     *
     * @param from Point to start sync from
     * @param config Pipeline configuration
     * @param chainSyncListener Listener for chain sync events
     */
    public void startPipelinedSyncForNode(Point from, PipelineConfig config, ChainSyncAgentListener chainSyncListener) {
        startPipelinedSync(from, config, null, chainSyncListener, null);
    }

    /**
     * Enable selective body fetching - only fetch bodies for headers that pass the filter
     *
     * @param filter Predicate to determine which headers should have bodies fetched
     */
    public void enableSelectiveBodyFetch(Predicate<BlockHeader> filter) {
        if (n2NPeerFetcher == null) {
            throw new IllegalStateException("N2NPeerFetcher not initialized");
        }
        n2NPeerFetcher.enableSelectiveBodyFetch(filter);
    }

    /**
     * Manually fetch block bodies for specific points
     * Useful for selective or on-demand body fetching
     *
     * @param points List of points to fetch bodies for
     */
    public void fetchBlockBodies(List<Point> points) {
        if (n2NPeerFetcher == null) {
            throw new IllegalStateException("N2NPeerFetcher not initialized");
        }
        n2NPeerFetcher.fetchBlockBodies(points);
    }

    /**
     * Set the pipelining strategy
     *
     * @param strategy Pipeline strategy to use
     */
    public void setPipelineStrategy(PipelineStrategy strategy) {
        if (n2NPeerFetcher == null) {
            throw new IllegalStateException("N2NPeerFetcher not initialized");
        }
        n2NPeerFetcher.setPipelineStrategy(strategy);
    }

    /**
     * Get current pipeline metrics for monitoring performance
     *
     * @return Current pipeline metrics
     */
    public PipelineMetrics getPipelineMetrics() {
        if (n2NPeerFetcher == null) {
            throw new IllegalStateException("N2NPeerFetcher not initialized");
        }
        return n2NPeerFetcher.getPipelineMetrics();
    }

    /**
     * Get current pipeline configuration
     *
     * @return Current pipeline configuration
     */
    public PipelineConfig getPipelineConfig() {
        if (n2NPeerFetcher == null) {
            throw new IllegalStateException("N2NPeerFetcher not initialized");
        }
        return n2NPeerFetcher.getPipelineConfig();
    }

    /**
     * Update pipeline configuration (only affects new operations)
     *
     * @param config New pipeline configuration
     */
    public void updatePipelineConfig(PipelineConfig config) {
        if (n2NPeerFetcher == null) {
            throw new IllegalStateException("N2NPeerFetcher not initialized");
        }
        n2NPeerFetcher.updatePipelineConfig(config);
    }

    // ========================================
    // CONVENIENCE METHODS FOR COMMON PATTERNS
    // ========================================

    /**
     * Start headers-only sync for fast header synchronization
     *
     * @param from Point to start sync from
     * @param chainSyncListener Listener for header events
     */
    public void startHeadersOnlySync(Point from, ChainSyncAgentListener chainSyncListener) {
        startChainSyncOnly(from, chainSyncListener);
    }

    /**
     * Start high-performance sync for node implementations
     * Optimized for maximum throughput with large pipeline depths
     *
     * @param from Point to start sync from
     * @param chainSyncListener Listener for chain sync events
     */
    public void startHighPerformanceSync(Point from, ChainSyncAgentListener chainSyncListener) {
        PipelineConfig nodeConfig = PipelineConfig.highPerformanceNodeConfig();
        startPipelinedSyncForNode(from, nodeConfig, chainSyncListener);
    }

    /**
     * Start low-resource sync for resource-constrained environments
     *
     * @param from Point to start sync from
     * @param blockChainDataListener Listener for block events
     */
    public void startLowResourceSync(Point from, BlockChainDataListener blockChainDataListener) {
        PipelineConfig lowResourceConfig = PipelineConfig.lowResourceConfig();
        startPipelinedSync(from, lowResourceConfig, blockChainDataListener, null, null);
    }

    class KeepAliveThreadListener implements BlockChainDataListener {
        private Thread keepAliveThread;

        @Override
        public void intersactFound(Tip tip, Point point) {
            if (keepAliveThread == null || !keepAliveThread.isAlive()) {
                if (!n2NPeerFetcher.isRunning())
                    return;
                log.info("Starting keep alive thread for peer: " + host + ":" + port);
                keepAliveThread = Thread.ofVirtual().unstarted(() -> {
                    int interval = 10000; // 10 seconds
                    while (true) {

                        if (!n2NPeerFetcher.isRunning()) {
                            log.info("Keep alive thread stopping as peer is not running anymore: " + host + ":" + port);
                            break;
                        }

                        try {
                            Thread.sleep(interval);
                            int randomNo = getRandomNumber(0, 60000);

                            if (log.isDebugEnabled()) {
                                log.debug("Last keep alive response cookie: " + n2NPeerFetcher.getLastKeepAliveResponseCookie());
                                log.debug("Sending keep alive : " + randomNo);
                            }

                            n2NPeerFetcher.sendKeepAliveMessage(randomNo);

                        } catch (InterruptedException e) {
                            log.info("Keep alive thread interrupted");
                            break;
                        }
                    }
                });
                keepAliveThread.start();
            }
        }

        @Override
        public void onDisconnect() {
            try {
                if (keepAliveThread != null && keepAliveThread.isAlive()) {
                    keepAliveThread.interrupt();
                    log.info("Stopping keep alive thread for peer: " + host + ":" + port);
                }
            } catch (Exception e) {
                log.error("Error stopping keep alive thread", e);
            }
        }

        private int getRandomNumber(int min, int max) {
            return (int) ((Math.random() * (max - min)) + min);
        }
    }

}
