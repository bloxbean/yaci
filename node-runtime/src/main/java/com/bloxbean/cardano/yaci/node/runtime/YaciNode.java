package com.bloxbean.cardano.yaci.node.runtime;

import com.bloxbean.cardano.yaci.core.config.YaciConfig;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbBlock;
import com.bloxbean.cardano.yaci.core.model.byron.ByronMainBlock;
import com.bloxbean.cardano.yaci.core.network.server.NodeServer;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.helper.*;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.n2n.ChainSyncAgentListener;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.byron.ByronBlockHead;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbHead;
import com.bloxbean.cardano.yaci.helper.model.Transaction;
import com.bloxbean.cardano.yaci.node.api.NodeAPI;
import com.bloxbean.cardano.yaci.node.api.SyncPhase;
import com.bloxbean.cardano.yaci.node.api.config.YaciNodeConfig;
import com.bloxbean.cardano.yaci.node.api.listener.NodeEventListener;
import com.bloxbean.cardano.yaci.node.api.model.NodeStatus;
import com.bloxbean.cardano.yaci.node.runtime.chain.InMemoryChainState;
import com.bloxbean.cardano.yaci.node.runtime.chain.DirectRocksDBChainState;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hybrid Yaci Node - Acts as both client and server
 *
 * CLIENT MODE: Syncs with real Cardano nodes (preprod relay nodes)
 * SERVER MODE: Serves other Yaci clients with blockchain data
 *
 * This enables Yaci to act as a bridge/relay node
 */
@Slf4j
public class YaciNode implements NodeAPI, BlockChainDataListener, ChainSyncAgentListener {

    // Configuration
    private final YaciNodeConfig config;

    // Client components (for syncing with remote nodes)
    private final String remoteCardanoHost;
    private final int remoteCardanoPort;
    private final long protocolMagic;
    private final ChainState chainState;

    // Client sync component - now using pipelined sync for parallel ChainSync and BlockFetch
    private PeerClient peerClient;
    private boolean isInitialSyncComplete = false;
    private boolean isBulkBatchSync = false;

    // Pipelining state
    private PipelineConfig pipelineConfig;
    private boolean isPipelinedMode = false;
    private long headersReceived = 0;
    private long bodiesReceived = 0;

    // Remote tip info for sync strategy
    private com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip remoteTip;

    // Server components (for serving other clients)
    private NodeServer nodeServer;
    private final int serverPort;

    // Status tracking
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean isSyncing = new AtomicBoolean(false);
    private final AtomicBoolean isServerRunning = new AtomicBoolean(false);
    private final AtomicBoolean disconnectLogged = new AtomicBoolean(false);

    // Statistics
    private long blocksProcessed = 0;
    private long lastProcessedSlot = 0;

    // Rollback classification fields
    private SyncPhase syncPhase = SyncPhase.INITIAL_SYNC;
    private ChainTip lastKnownChainTip;
    private long rollbackClassificationTimeout = 30000; // 30 seconds
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private final CopyOnWriteArrayList<BlockChainDataListener> blockChainDataListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<NodeEventListener> nodeEventListeners = new CopyOnWriteArrayList<>();

    public YaciNode(YaciNodeConfig config) {
        this.config = config;
        this.remoteCardanoHost = config.getRemoteHost();
        this.remoteCardanoPort = config.getRemotePort();
        this.protocolMagic = config.getProtocolMagic();
        this.serverPort = config.getServerPort();

        // Initialize ChainState based on configuration
        if (config.isUseRocksDB()) {
            this.chainState = new DirectRocksDBChainState(config.getRocksDBPath());
        } else {
            this.chainState = new InMemoryChainState();
        }

        // Configure Yaci
        YaciConfig.INSTANCE.setReturnBlockCbor(true);
        YaciConfig.INSTANCE.setReturnTxBodyCbor(true);

        // Initialize pipeline configuration
        this.pipelineConfig = createPipelineConfig();

        log.info("Hybrid Yaci Node initialized");
        log.info("Remote: {}:{} (magic: {})", remoteCardanoHost, remoteCardanoPort, protocolMagic);
        log.info("Server port: {}", serverPort);
        log.info("Storage: {}", config.isUseRocksDB() ? "RocksDB" : "InMemory");
        log.info("Pipeline config: {}", pipelineConfig);
    }

    /**
     * Create pipeline configuration using values from HybridNodeConfig
     */
    private PipelineConfig createPipelineConfig() {
        return PipelineConfig.builder()
                .headerPipelineDepth(config.getHeaderPipelineDepth())
                .bodyBatchSize(config.getBodyBatchSize())
                .maxParallelBodies(config.getMaxParallelBodies())
                .batchTimeout(Duration.ofSeconds(2))  // Quick batch processing
                .enableParallelProcessing(true)       // Concurrent processing
                .processingThreads(4)      // Multiple threads for performance
                .headerBufferSize(config.getHeaderPipelineDepth() * 5)  // Buffer size based on pipeline depth
                .build();
    }

    /**
     * Create an adaptive selective body fetch strategy
     * This determines which block bodies to fetch based on configuration and current sync state
     */
    private java.util.function.Predicate<BlockHeader> createSelectiveBodyFetchStrategy() {
        return header -> {
            try {
                // If selective body fetch is disabled, fetch all bodies
                if (!config.isEnableSelectiveBodyFetch()) {
                    return true;
                }

                long slot = header.getHeaderBody().getSlot();
                long blockNumber = header.getHeaderBody().getBlockNumber();
                int fetchRatio = config.getSelectiveBodyFetchRatio();

                // During initial sync, be more selective to improve performance
                if (!isInitialSyncComplete) {
                    // Use configured ratio for bulk sync
                    if (fetchRatio == 0 || blockNumber % fetchRatio == 0) {
                        return true;
                    }

                    // Always fetch recent blocks (last 100 slots) to maintain tip accuracy
                    if (remoteTip != null && (remoteTip.getPoint().getSlot() - slot) < 100) {
                        return true;
                    }

                    // Always fetch epoch boundary blocks (assuming 21600 slots per epoch for most eras)
                    if (slot % 21600 < 100) {
                        return true;
                    }

                    return false;
                } else {
                    // During real-time sync, fetch all bodies for immediate serving
                    return true;
                }

            } catch (Exception e) {
                if (log.isDebugEnabled()) {
                    log.debug("Error in selective body fetch strategy, defaulting to fetch: {}", e.getMessage());
                }
                return true; // Default to fetching when in doubt
            }
        };
    }

    /**
     * Start the hybrid node (both client and server)
     */
    public void start() {
        if (isRunning.compareAndSet(false, true)) {
            log.info("Starting Hybrid Yaci Node...");

            // Start server first
            if (config.isEnableServer()) {
                startServer();
            }

            // Start client sync
            if (config.isEnableClient()) {
                startClientSync();
            }

            log.info("Hybrid Yaci Node started successfully");

            // Print startup status
            printStartupStatus();
        } else {
            log.warn("Node is already running");
        }
    }

    /**
     * Start the server component
     */
    private void startServer() {
        try {
            log.info("Starting NodeServer on port {}...", serverPort);
            log.info("Protocol magic: {}", protocolMagic);

            ChainTip tip = chainState.getTip();
            if (tip != null) {
                log.info("Server starting with tip: slot={}, blockNumber={}, hash={}",
                        tip.getSlot(), tip.getBlockNumber(), HexUtil.encodeHexString(tip.getBlockHash()));

                // Test if we can serve the genesis/first block
                try {
                    if (chainState instanceof InMemoryChainState) {
                        Point firstBlock = ((InMemoryChainState) chainState).getFirstBlock();
                        log.info("First block available: {}", firstBlock);
                    } else if (chainState instanceof DirectRocksDBChainState) {
                        Point firstBlock = ((DirectRocksDBChainState) chainState).getFirstBlock();
                        log.info("First block available: {}", firstBlock);
                    }
                } catch (Exception e) {
                    log.warn("Error checking first block", e);
                }
            } else {
                log.error("❌ CRITICAL: Server starting with empty chain state (no tip)");
                log.error("❌ Real Cardano nodes will not connect to an empty server");
                log.error("❌ HybridNode must sync some blockchain data first before serving");
            }

            nodeServer = new NodeServer(serverPort,
                    N2NVersionTableConstant.v11AndAbove(protocolMagic, false, 0, false),
                    chainState);

            Thread serverThread = new Thread(() -> {
                try {
                    nodeServer.start();
                } catch (Exception e) {
                    log.error("NodeServer failed", e);
                    isServerRunning.set(false);
                }
            });

            serverThread.setDaemon(false);
            serverThread.setName("YaciNodeServer");
            serverThread.start();

            // Give server time to start
            Thread.sleep(2000);
            isServerRunning.set(true);

            log.info("NodeServer started successfully on port {}", serverPort);
            log.info("Server is ready to accept connections from Cardano nodes");
        } catch (Exception e) {
            log.error("Failed to start NodeServer", e);
            throw new RuntimeException("Failed to start server", e);
        }
    }

    /**
     * Start the client sync component using either pipelined or sequential sync
     */
    private void startClientSync() {
        try {
            boolean usePipeline = config.isEnablePipelinedSync();
            log.info("Starting {} client sync with {}:{}...",
                    usePipeline ? "pipelined" : "sequential", remoteCardanoHost, remoteCardanoPort);
            isSyncing.set(true);
            isPipelinedMode = usePipeline;

            // Get local tip to determine sync strategy
            ChainTip localTip = chainState.getTip();
            log.info("Local tip: {}", localTip);

            // Initialize last known tip
            lastKnownChainTip = localTip;

            // Determine starting point for sync
            Point startPoint = determineStartPoint(localTip);
            log.info("Starting pipelined sync from point: {}", startPoint);

            // Find remote tip to understand sync scope

            // Create PeerClient
            if (peerClient == null) {
                peerClient = new PeerClient(remoteCardanoHost, remoteCardanoPort, protocolMagic, startPoint);
                peerClient.connect(this, null);
            }

            if (isPipelinedMode) {
                startPipelinedClientSync(localTip, remoteTip, startPoint);
            } else {
                startSequentialClientSync(startPoint);
            }

        } catch (Exception e) {
            log.error("Failed to start client sync", e);
            isSyncing.set(false);
            isPipelinedMode = false;
            throw new RuntimeException("Failed to start client sync", e);
        }
    }

    /**
     * Start pipelined client sync with parallel ChainSync and BlockFetch
     */
    private void startPipelinedClientSync(ChainTip localTip, com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip remoteTip, Point startPoint) {
        // Reset sync phase when starting new sync
        syncPhase = SyncPhase.INITIAL_SYNC;
        log.info("ChainSync agent started - reset to INITIAL_SYNC phase");
        // Determine sync strategy based on local vs remote tip
        boolean shouldUseBulkSync = shouldUseBulkSync(localTip, remoteTip.getPoint());

        if (shouldUseBulkSync) {
            log.info("🚀 ==> BULK PIPELINED SYNC: {} slots behind, using high-performance pipeline",
                    remoteTip.getPoint().getSlot() - (localTip != null ? localTip.getSlot() : 0));
            log.info("🚀 ==> Headers will arrive first, bodies will be fetched in parallel");
            isInitialSyncComplete = false;

            // Use high-performance pipeline config for bulk sync
            pipelineConfig = PipelineConfig.builder()
                    .headerPipelineDepth(300)  // Very aggressive header fetching
                    .bodyBatchSize(100)        // Large batches for bulk sync
                    .maxParallelBodies(15)     // Maximum parallelism
                    .batchTimeout(Duration.ofSeconds(1))
                    .enableParallelProcessing(true)
                    .processingThreads(6)
                    .headerBufferSize(1500)
                    .build();
        } else {
            log.info("🚀 ==> REAL-TIME PIPELINED SYNC: Near tip, using optimized real-time pipeline");
            isInitialSyncComplete = true;

            // Use standard pipeline config for real-time sync
            pipelineConfig = createPipelineConfig();
        }

        // Start pipelined sync with both header and body listeners
        log.info("🚀 ==> Starting pipelined sync with config: {}", pipelineConfig);
        peerClient.startPipelinedSync(startPoint, pipelineConfig, this, this, null);

        // Enable selective body fetching with adaptive strategy
        peerClient.enableSelectiveBodyFetch(createSelectiveBodyFetchStrategy());

        // Use FULL_PARALLEL strategy for maximum performance
        peerClient.setPipelineStrategy(PipelineStrategy.FULL_PARALLEL);

        // Start monitoring
        startPipelineMonitor();
    }

    /**
     * Start sequential client sync (traditional mode for performance comparison)
     */
    private void startSequentialClientSync(Point startPoint) {
        log.info("📦 ==> SEQUENTIAL SYNC: Using traditional header+body sync");
        isInitialSyncComplete = false;

        // Start traditional sync from tip or point
        peerClient.startSync(startPoint);

        log.info("📦 ==> Sequential sync started from point: {}", startPoint);
    }

    /**
     * Determine the starting point for sync based on local chain state
     */
    private Point determineStartPoint(ChainTip localTip) {
        if (localTip == null) {
            log.info("No local tip found, starting from genesis");

            if (config.getSyncStartSlot() > 0) {
                log.info("Using configured sync start slot: {}", config.getSyncStartSlot());
                return new Point(config.getSyncStartSlot(), config.getSyncStartBlockHash());
            }

            return Point.ORIGIN;
        }

        log.info("Local tip found at slot {}, starting sync from there", localTip.getSlot());
        return new Point(localTip.getSlot(), HexUtil.encodeHexString(localTip.getBlockHash()));
    }

    /**
     * Determine if we should use bulk sync based on local tip age
     */
    private boolean shouldUseBulkSync(ChainTip localTip, Point chainTip) {
        if (localTip == null) {
            log.info("No local tip, using bulk sync to get initial blockchain data");
            return true;
        }

        long slotDifference = chainTip.getSlot() - localTip.getSlot();

        if (slotDifference > config.getFullSyncThreshold()) {
            log.info("Local tip is {} slots behind (> {} threshold), using bulk sync",
                    slotDifference, config.getFullSyncThreshold());
            return true;
        } else {
            log.info("Local tip is {} slots behind (<= {} threshold), using real-time sync",
                    slotDifference, config.getFullSyncThreshold());
            return false;
        }
    }

    /**
     * Start pipeline monitoring for performance tracking
     */
    private void startPipelineMonitor() {
        Thread monitorThread = new Thread(() -> {
            try {
                while (isSyncing.get() && isPipelinedMode) {
                    try {
                        if (peerClient != null) {
                            PipelineMetrics metrics = peerClient.getPipelineMetrics();
                            if (metrics != null) {
                                // Update local counters
                                long currentHeaders = metrics.getHeadersReceived().get();
                                long currentBodies = metrics.getBodiesReceived().get();

                                // Log pipeline progress every 30 seconds
                                if (currentHeaders > 0 || currentBodies > 0) {
                                    log.info("🔄 Pipeline Status: Headers: {} ({}/s), Bodies: {} ({}/s), Efficiency: {}%",
                                            currentHeaders, String.format("%.1f", metrics.getHeadersPerSecond()),
                                            currentBodies, String.format("%.1f", metrics.getBodiesPerSecond()),
                                            String.format("%.1f", metrics.getPipelineEfficiency() * 100));

                                    // Check if we're catching up (bulk sync complete)
                                    if (!isInitialSyncComplete && remoteTip != null) {
                                        long slotDifference = remoteTip.getPoint().getSlot() - lastProcessedSlot;
                                        if (slotDifference <= 20) {
                                            isInitialSyncComplete = true;
                                            log.info("🚀 ==> PIPELINE SYNC COMPLETE: Now in real-time mode");
                                        }
                                    }
                                }
                            }
                        }
                        Thread.sleep(30000); // Check every 30 seconds
                    } catch (Exception e) {
                        log.debug("Pipeline monitor error: {}", e.getMessage());
                        Thread.sleep(10000);
                    }
                }
            } catch (InterruptedException e) {
                log.info("Pipeline monitor interrupted");
                Thread.currentThread().interrupt();
            }
        });

        monitorThread.setDaemon(true);
        monitorThread.setName("YaciPipelineMonitor");
        monitorThread.start();

        log.info("Pipeline monitor started");
    }

    /**
     * Start background tip finder to get accurate remote tip information
     */
    private void startRemoteTipFinder() {
        Thread tipFinderThread = new Thread(() -> {
            try {
                // Wait a bit before starting tip finder to avoid startup conflicts
                Thread.sleep(10000);

                while (isSyncing.get()) {
                    try {
                        TipFinder tipFinder = new TipFinder(remoteCardanoHost, remoteCardanoPort,
                                Point.ORIGIN, protocolMagic);
                        var remoteTip = tipFinder.find().block(Duration.ofSeconds(5));

                        if (remoteTip != null) {
                            this.remoteTip = remoteTip;
                            log.debug("Remote tip updated: slot={}, hash={}",
                                    remoteTip.getPoint().getSlot(), remoteTip.getPoint().getHash());
                        }

                        tipFinder.shutdown();

                        // Check every 30 seconds
                        Thread.sleep(30000);

                    } catch (Exception e) {
                        log.debug("Could not get remote tip: {}", e.getMessage());
                        Thread.sleep(30000);
                    }
                }
            } catch (InterruptedException e) {
                log.debug("Remote tip finder interrupted");
                Thread.currentThread().interrupt();
            }
        });

        tipFinderThread.setDaemon(true);
        tipFinderThread.setName("YaciRemoteTipFinder");
        tipFinderThread.start();

        log.info("Background remote tip finder started");
    }

    /**
     * Start a background monitor to handle sync progress and automatic transitions
     */
    private void startSyncMonitor() {
        Thread monitorThread = new Thread(() -> {
            try {
                // Monitor for initial sync completion and handle BlockFetch to ChainSync transition
                monitorSyncProgress();
            } catch (Exception e) {
                log.error("Sync monitor error", e);
            }
        });
        monitorThread.setDaemon(true);
        monitorThread.setName("YaciSyncMonitor");
        monitorThread.start();

        log.info("Sync monitor started");
    }

    /**
     * Monitor sync progress and handle automatic transitions between protocols
     */
    private void monitorSyncProgress() {
        try {
            // Wait for initial connection establishment
            Thread.sleep(5000);

            while (isSyncing.get() && peerClient != null && peerClient.isRunning()) {
                try {
                    // Monitor sync health
                    if (blocksProcessed % 100 == 0 && blocksProcessed > 0) {
                        log.info("Sync progress: {} blocks processed, current slot: {}",
                                blocksProcessed, lastProcessedSlot);
                    }

                    Thread.sleep(10000); // Check every 10 seconds

                } catch (Exception e) {
                    log.warn("Error in sync monitoring", e);
                    Thread.sleep(5000);
                }
            }
        } catch (InterruptedException e) {
            log.info("Sync monitor interrupted");
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Sync monitor failed", e);
        }
    }


    /**
     * Check sync progress and detect when BlockFetch is complete to transition to ChainSync
     */
    private void checkSyncProgress() {
        // If we have a remote tip and we're close to it, mark initial sync as complete
        if (!isInitialSyncComplete && remoteTip != null) {
            long slotDifference = remoteTip.getPoint().getSlot() - lastProcessedSlot;

            // If we're within 10 slots of the remote tip, consider initial sync complete
            if (slotDifference <= 10) {
                isInitialSyncComplete = true;
                log.info("🚀 ==> TRANSITION: BlockFetch → ChainSync");
                log.info("🚀 ==> Initial BlockFetch sync complete! Now in real-time ChainSync mode at slot {}", lastProcessedSlot);
                log.info("🚀 ==> Hybrid Yaci Node is now fully synchronized and serving clients");
                log.info("🚀 ==> Will now log every block as it arrives in real-time");
            }
        }
    }

    /**
     * Stop the hybrid node
     */
    public void stop() {
        if (isRunning.compareAndSet(true, false)) {
            log.info("Stopping Hybrid Yaci Node...");

            // Stop client sync
            if (isSyncing.get()) {
                try {
                    if (peerClient != null && peerClient.isRunning()) {
                        log.info("Stopping PeerClient...");
                        peerClient.stop();
                    }
                } catch (Exception e) {
                    log.warn("Error stopping peerClient", e);
                }
                isSyncing.set(false);
            }

            // Stop server
            if (isServerRunning.get()) {
                if (nodeServer != null) {
                    nodeServer.shutdown();
                }
                isServerRunning.set(false);
            }

            // Shutdown scheduler
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }

            // Close ChainState if it's RocksDB
            if (chainState instanceof DirectRocksDBChainState) {
                ((DirectRocksDBChainState) chainState).close();
            }

            log.info("Hybrid Yaci Node stopped");
        }
    }

    // BlockChainDataListener implementation
    @Override
    public void onByronBlock(ByronMainBlock byronBlock) {
        storeByronBlock(byronBlock);
        blocksProcessed++;
        bodiesReceived++;
        lastProcessedSlot = byronBlock.getHeader().getConsensusData().getAbsoluteSlot();
        long blockNumber = byronBlock.getHeader().getConsensusData().getDifficulty().longValue();

        // Check if we've caught up to the remote tip (BlockFetch complete)
        checkSyncProgress();
        updateSyncPhase();

        // Update last known tip
        lastKnownChainTip = chainState.getTip();

        // Notify server agents about new block (only in real-time mode)
        if (isServerRunning.get() && isInitialSyncComplete) {
            try {
                nodeServer.notifyNewDataAvailable();
            } catch (Exception e) {
                log.warn("Error notifying server agents about new Byron block", e);
            }
        }

        // Enhanced logging for pipelined mode
        if (isPipelinedMode) {
            if (isInitialSyncComplete) {
                log.info("🔄 Real-time: Block #{} at slot {} (Byron) [H:{}, B:{}]",
                        blockNumber, lastProcessedSlot, headersReceived, bodiesReceived);
            } else {
                if (blocksProcessed % 20 == 0) {
                    log.info("📦 Bodies: {} received (Block #{} at slot {}) (Byron) [H:{}, B:{}]",
                            bodiesReceived, blockNumber, lastProcessedSlot, headersReceived, bodiesReceived);
                }
            }
        } else {
            // Legacy mode logging
            if (isInitialSyncComplete) {
                log.info("ChainSync: Block #{} at slot {} (Byron)", blockNumber, lastProcessedSlot);
            } else {
                if (blocksProcessed % 100 == 0) {
                    log.info("BlockFetch: Processed {} blocks, current slot: {} (Byron)", blocksProcessed, lastProcessedSlot);
                }
            }
        }
    }

    @Override
    public void onByronEbBlock(ByronEbBlock byronEbBlock) {
        storeByronEbBlock(byronEbBlock);
        blocksProcessed++;
        bodiesReceived++;
        lastProcessedSlot = byronEbBlock.getHeader().getConsensusData().getAbsoluteSlot();
        long blockNumber = byronEbBlock.getHeader().getConsensusData().getDifficulty().longValue();

        // Check if we've caught up to the remote tip (BlockFetch complete)
        checkSyncProgress();
        updateSyncPhase();

        // Update last known tip
        lastKnownChainTip = chainState.getTip();

        // Notify server agents about new block (only in real-time mode)
        if (isServerRunning.get() && isInitialSyncComplete) {
            try {
                nodeServer.notifyNewDataAvailable();
            } catch (Exception e) {
                log.warn("Error notifying server agents about new Byron EB block", e);
            }
        }

        // Enhanced logging for pipelined mode
        if (isPipelinedMode) {
            if (isInitialSyncComplete) {
                log.info("🔄 Real-time: Block #{} at slot {} (Byron EB) [H:{}, B:{}]",
                        blockNumber, lastProcessedSlot, headersReceived, bodiesReceived);
            } else {
                if (blocksProcessed % 20 == 0) {
                    log.info("📦 Bodies: {} received (Block #{} at slot {}) (Byron EB) [H:{}, B:{}]",
                            bodiesReceived, blockNumber, lastProcessedSlot, headersReceived, bodiesReceived);
                }
            }
        } else {
            // Legacy mode logging
            if (isInitialSyncComplete) {
                log.info("ChainSync: Block #{} at slot {} (Byron EB)", blockNumber, lastProcessedSlot);
            } else {
                if (blocksProcessed % 100 == 0) {
                    log.info("BlockFetch: Processed {} blocks, current slot: {} (Byron EB)", blocksProcessed, lastProcessedSlot);
                }
            }
        }
    }

    @Override
    public void onBlock(Era era, Block block, List<Transaction> transactions) {
        storeShelleyBlock(block);
        blocksProcessed++;
        bodiesReceived++;
        lastProcessedSlot = block.getHeader().getHeaderBody().getSlot();
        long blockNumber = block.getHeader().getHeaderBody().getBlockNumber();

        if (log.isDebugEnabled()) {
            log.debug("Successfully processed block: era={}, blockNumber={}, slot={}",
                    era, blockNumber, lastProcessedSlot);
        }

        // Check if we've caught up to the remote tip (BlockFetch complete)
        checkSyncProgress();
        updateSyncPhase();

        // Update last known tip
        lastKnownChainTip = chainState.getTip();

        // Notify server agents about new block (only in real-time mode)
        if (isServerRunning.get() && isInitialSyncComplete) {
            try {
                nodeServer.notifyNewDataAvailable();
            } catch (Exception e) {
                log.warn("Error notifying server agents about new {} block", era, e);
            }
        }

        // Enhanced logging for pipelined mode
        if (isPipelinedMode) {
            if (isInitialSyncComplete) {
                // Real-time mode - log every block with pipeline info
                log.info("🔄 Real-time: Block #{} at slot {} ({}) [H:{}, B:{}]",
                        blockNumber, lastProcessedSlot, era, headersReceived, bodiesReceived);
            } else {
                // Bulk sync mode - log every 20 blocks with pipeline progress
                if (blocksProcessed % 20 == 0) {
                    log.info("📦 Bodies: {} received (Block #{} at slot {}) [H:{}, B:{}]",
                            bodiesReceived, blockNumber, lastProcessedSlot, headersReceived, bodiesReceived);
                }
            }
        } else {
            // Legacy mode logging
            if (isInitialSyncComplete) {
                log.info("ChainSync: Block #{} at slot {} ({})", blockNumber, lastProcessedSlot, era);
            } else {
                if (blocksProcessed % 100 == 0) {
                    log.info("BlockFetch: Current block: {}, slot: {}",  blockNumber, lastProcessedSlot);
                }
            }
        }
    }

    @Override
    public void onRollback(Point point) {
        var localTip = chainState.getTip();
        long rollbackSlot = point.getSlot();

        if (rollbackSlot == 0) {
            log.warn("Rollback requested to genesis (slot 0) - no action taken");
            return;
        }

        // Protection against catastrophic rollbacks
        if (localTip != null && localTip.getBlockNumber() > 1000 && rollbackSlot == 0) {
            log.error("🚨 CATASTROPHIC ROLLBACK DETECTED! 🚨");
            log.error("Current tip: slot={}, block={}", localTip.getSlot(), localTip.getBlockNumber());
            log.error("Rollback requested to: slot={}", rollbackSlot);
            log.error("This is likely a protocol error - preventing data loss!");
            log.error("Stack trace for debugging:");

            // Print full stack trace for debugging
            Thread.dumpStack();

            // Exit system to prevent data corruption
            log.error("EMERGENCY EXIT - Check logs for debugging information");
            System.exit(1);
            return;
        }

        // Classify rollback type
        boolean isReal = isRealRollback(point);

        // Perform rollback
        chainState.rollbackTo(rollbackSlot);

        log.info("ROLLBACK_EVENT: slot={}, type={}, phase={}, serverNotified={}",
                rollbackSlot, isReal ? "REAL_REORG" : "RECONNECTION", syncPhase, isReal && isServerRunning.get());

        log.info("Rollback to slot: {} (from tip: {}) - Type: {}",
                rollbackSlot,
                localTip != null ? String.format("slot=%d, block=%d", localTip.getSlot(), localTip.getBlockNumber()) : "null",
                isReal ? "REAL_REORG" : "RECONNECTION");

        // Notify server agents only for real rollbacks
        if (isReal && isServerRunning.get()) {
            try {
                nodeServer.notifyNewDataAvailable();
                log.info("Notified server agents about chain reorganization");
            } catch (Exception e) {
                log.warn("Error notifying server agents about rollback", e);
            }
        }

        // Update last known tip
        lastKnownChainTip = chainState.getTip();
    }

    @Override
    public void batchDone() {
        if (isBulkBatchSync) {
            isBulkBatchSync = false; // Reset bulk sync flag
            log.info("Batch sync complete - activate ChainSync mode");
            startClientSync();
        }
    }


    @Override
    public void intersactNotFound(Tip tip) {
        var localTip = chainState.getTip();
        log.warn("Intersect not found. Local tip: {}, Remote tip: {}", localTip, tip);
        if (localTip != null) {
            long rollbackSlot = Math.max(0, localTip.getSlot() - 300); // Ensure we don't go negative

            // Extra protection: if we have significant progress, don't rollback to genesis
            if (localTip.getBlockNumber() > 1000 && rollbackSlot == 0) {
                log.error("🚨 INTERSECT NOT FOUND - WOULD CAUSE MASSIVE ROLLBACK! 🚨");
                log.error("Current tip: slot={}, block={}", localTip.getSlot(), localTip.getBlockNumber());
                log.error("Calculated rollback to: slot={}", rollbackSlot);
                log.error("This suggests a serious chain mismatch - preventing data loss!");
                log.error("Stack trace for debugging:");
                Thread.dumpStack();
                log.error("EMERGENCY EXIT - Check logs for debugging information");
                System.exit(1);
                return;
            }

            chainState.rollbackTo(rollbackSlot);
            log.warn("Rolled back 300 slots to: {} (from tip: slot={}, block={})",
                    rollbackSlot, localTip.getSlot(), localTip.getBlockNumber());
        } else {
            log.warn("Local tip is empty - no rollback needed");
        }
    }

    @Override
    public void onDisconnect() {
        // Prevent multiple disconnect log messages within a short time window
        if (disconnectLogged.compareAndSet(false, true)) {
            log.warn("Disconnected from remote node - will attempt to reconnect");

            // Reset the flag after 5 seconds to allow future disconnect logging
            Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(5000);
                    disconnectLogged.set(false);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        // Don't immediately set syncing to false for pipelined sync with auto-reconnection
        // The sync will be marked as stopped only if the connection cannot be re-established
        if (!isPipelinedMode) {
            isSyncing.set(false);
        }
    }

    // Private helper methods

    /**
     * Validate chain continuity by checking if the previous block exists
     * Exits the system if a gap is detected to prevent corrupt chain state
     * @param prevBlockHash Previous block hash (null for genesis)
     * @param currentBlockNumber Current block number
     * @param currentSlot Current slot
     * @param currentBlockHash Current block hash (for logging)
     */
    private void validateChainContinuity(String prevBlockHash, long currentBlockNumber,
                                         long currentSlot, String currentBlockHash) {
        try {
            // Skip validation for genesis block
            if (currentBlockNumber == 0 || prevBlockHash == null || prevBlockHash.isEmpty()) {
                if (log.isDebugEnabled()) {
                    log.debug("Skipping chain continuity check for genesis block: {}", currentBlockNumber);
                }
                return;
            }

            // Check if previous block exists in storage
            byte[] prevBlockHashBytes = HexUtil.decodeHexString(prevBlockHash);
            byte[] prevBlock = chainState.getBlock(prevBlockHashBytes);
            byte[] prevHeader = chainState.getBlockHeader(prevBlockHashBytes);

            if (prevBlock == null && prevHeader == null) {
                log.error("🚨 CRITICAL: CHAIN CONTINUITY GAP DETECTED! 🚨");
                log.error("Missing previous block when trying to store block #{} at slot {}", currentBlockNumber, currentSlot);
                log.error("Current block hash: {}", currentBlockHash);
                log.error("Missing previous block hash: {}", prevBlockHash);
                log.error("Previous block number would be: {}", currentBlockNumber - 1);
                log.error("");
                log.error("This indicates a serious gap in blockchain data synchronization!");
                log.error("The chain state would be incomplete and unreliable if we continue.");
                log.error("");
                log.error("STOPPING PROCESS to prevent data corruption.");
                log.error("A recovery mechanism needs to be implemented to fetch missing blocks.");

                // Exit immediately to prevent corrupt chain state
                System.exit(1);
            }

            if (log.isDebugEnabled()) {
                log.debug("Chain continuity validated for block #{}: previous block {} exists",
                        currentBlockNumber, prevBlockHash);
            }

        } catch (Exception e) {
            log.error("Error validating chain continuity for block #{}: {}", currentBlockNumber, e.getMessage());
            log.error("Stopping process due to validation error to ensure data integrity");
            System.exit(1);
        }
    }

    private void storeByronBlock(ByronMainBlock byronBlock) {
        long blockNumber = byronBlock.getHeader().getConsensusData().getDifficulty().longValue();
        try {
            byte[] blockHash = HexUtil.decodeHexString(byronBlock.getHeader().getBlockHash());
            long slot = byronBlock.getHeader().getConsensusData().getAbsoluteSlot();
            byte[] blockCbor = HexUtil.decodeHexString(byronBlock.getCbor());

            // Validate chain continuity
            validateChainContinuity(byronBlock.getHeader().getPrevBlock(), blockNumber, slot,
                    byronBlock.getHeader().getBlockHash());

            // Store complete block
            chainState.storeBlock(blockHash, blockNumber, slot, blockCbor);

        } catch (Exception e) {
            log.error("Error storing Byron block", e);
            throw new RuntimeException("Failed to store Byron block " + blockNumber, e);
        }
    }

    private void storeByronEbBlock(ByronEbBlock byronEbBlock) {
        long blockNumber = byronEbBlock.getHeader().getConsensusData().getDifficulty().longValue();
        try {
            byte[] blockHash = HexUtil.decodeHexString(byronEbBlock.getHeader().getBlockHash());
            long slot = byronEbBlock.getHeader().getConsensusData().getAbsoluteSlot();
            byte[] blockCbor = HexUtil.decodeHexString(byronEbBlock.getCbor());

            // Validate chain continuity
            validateChainContinuity(byronEbBlock.getHeader().getPrevBlock(), blockNumber, slot,
                    byronEbBlock.getHeader().getBlockHash());

            // Store complete block
            chainState.storeBlock(blockHash, blockNumber, slot, blockCbor);

        } catch (Exception e) {
            log.error("Error storing Byron EB block", e);
            throw new RuntimeException("Failed to store Byron EB block " + blockNumber, e);
        }
    }

    private void storeShelleyBlock(Block block) {
        long blockNumber = block.getHeader().getHeaderBody().getBlockNumber();
        try {
            byte[] blockHash = HexUtil.decodeHexString(block.getHeader().getHeaderBody().getBlockHash());
            long slot = block.getHeader().getHeaderBody().getSlot();
            byte[] blockCbor = HexUtil.decodeHexString(block.getCbor());

            // Validate chain continuity
            validateChainContinuity(block.getHeader().getHeaderBody().getPrevHash(), blockNumber, slot,
                    block.getHeader().getHeaderBody().getBlockHash());

            // Store complete block
            chainState.storeBlock(blockHash, blockNumber, slot, blockCbor);

        } catch (Exception e) {
            log.error("Error storing Shelley+ block", e);
            throw new RuntimeException("Failed to store Shelley+ block " + blockNumber, e);
        }
    }

    /**
     * Extracts the header CBOR from a full block's CBOR.
     * The block is expected to be an array where the header is the first element.
     *
     * @param blockCbor The CBOR bytes of the full block.
     * @return The CBOR bytes of the header, or null if extraction fails.
     */
    private byte[] extractHeaderFromBlock(byte[] blockCbor) {
        try {
            DataItem[] dataItems = CborSerializationUtil.deserialize(blockCbor);
            if (dataItems != null && dataItems.length > 0 && dataItems[0] instanceof Array) {
                Array blockArray = (Array) dataItems[0];
                if (!blockArray.getDataItems().isEmpty()) {
                    DataItem headerDI = blockArray.getDataItems().get(0);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    new CborEncoder(baos).encode(headerDI);
                    return baos.toByteArray();
                }
            }
            log.warn("Could not extract header; block CBOR format not as expected.");
            return null;
        } catch (Exception e) {
            log.warn("Failed to extract header from block CBOR", e);
            return null;
        }
    }

    /**
     * Determines if a rollback is a real chain reorganization or just a reconnection rollback
     */
    private boolean isRealRollback(Point rollbackPoint) {
        // Skip notification for initial/reconnection rollbacks
        if (syncPhase == SyncPhase.INTERSECT_PHASE || syncPhase == SyncPhase.INITIAL_SYNC) {
            log.info("Rollback during {} phase - skipping server notification", syncPhase);
            return false;
        }

        // Check if chain tip has genuinely moved backwards
        ChainTip currentTip = chainState.getTip();

        if (lastKnownChainTip != null &&
                rollbackPoint.getSlot() < lastKnownChainTip.getSlot() &&
                currentTip.getSlot() <= rollbackPoint.getSlot()) {
            log.info("Real chain reorganization detected - rollback from slot {} to slot {}",
                    lastKnownChainTip.getSlot(), rollbackPoint.getSlot());
            return true;
        }

        return false;
    }

    /**
     * Handle intersection found event - transition to INTERSECT_PHASE
     */
    public void onIntersectionFound() {
        syncPhase = SyncPhase.INTERSECT_PHASE;
        log.info("Transitioned to INTERSECT_PHASE - expect rollback to intersection");

        // Reset to steady state after timeout (handles normal post-intersection rollback)
        scheduler.schedule(() -> {
            if (syncPhase == SyncPhase.INTERSECT_PHASE) {
                syncPhase = SyncPhase.STEADY_STATE;
                log.info("Auto-transitioned to STEADY_STATE after intersection phase timeout");
            }
        }, rollbackClassificationTimeout, TimeUnit.MILLISECONDS);
    }

    /**
     * Update sync phase based on sync progress
     */
    private void updateSyncPhase() {
        if (syncPhase == SyncPhase.INITIAL_SYNC && isInitialSyncComplete) {
            syncPhase = SyncPhase.STEADY_STATE;
            log.info("Transitioned to STEADY_STATE sync phase");
        }
    }

    // Status and monitoring methods
    public boolean isRunning() {
        return isRunning.get();
    }

    public boolean isSyncing() {
        return isSyncing.get();
    }

    public boolean isServerRunning() {
        return isServerRunning.get();
    }

    public long getBlocksProcessed() {
        return blocksProcessed;
    }

    public long getLastProcessedSlot() {
        return lastProcessedSlot;
    }

    public ChainTip getLocalTip() {
        return chainState.getTip();
    }

    @Override
    public ChainState getChainState() {
        return chainState;
    }

    public YaciNodeConfig getConfig() {
        return config;
    }

    @Override
    public NodeStatus getStatus() {
        ChainTip localTip = getLocalTip();
        return NodeStatus.builder()
                .running(isRunning())
                .syncing(isSyncing())
                .serverRunning(isServerRunning())
                .blocksProcessed(blocksProcessed)
                .lastProcessedSlot(lastProcessedSlot)
                .localTipSlot(localTip != null ? localTip.getSlot() : null)
                .localTipBlockNumber(localTip != null ? localTip.getBlockNumber() : null)
                .remoteTipSlot(remoteTip != null ? remoteTip.getPoint().getSlot() : null)
                .remoteTipBlockNumber(remoteTip != null ? remoteTip.getBlock() : null)
                .initialSyncComplete(isInitialSyncComplete)
                .syncMode(isPipelinedMode ? "pipelined" : "sequential")
                .statusMessage("Node is " + (isRunning() ? "running" : "stopped"))
                .timestamp(System.currentTimeMillis())
                .build();
    }

    @Override
    public void addBlockChainDataListener(BlockChainDataListener listener) {
        if (listener != null && !blockChainDataListeners.contains(listener)) {
            blockChainDataListeners.add(listener);
        }
    }

    @Override
    public void removeBlockChainDataListener(BlockChainDataListener listener) {
        blockChainDataListeners.remove(listener);
    }

    @Override
    public void addNodeEventListener(NodeEventListener listener) {
        if (listener != null && !nodeEventListeners.contains(listener)) {
            nodeEventListeners.add(listener);
        }
    }

    @Override
    public void removeNodeEventListener(NodeEventListener listener) {
        nodeEventListeners.remove(listener);
    }

    /**
     * Print detailed startup status for debugging
     */
    private void printStartupStatus() {
        log.info("═══════════════════════════════════════════════════════════");
        log.info("🚀 HYBRID YACI NODE STARTUP STATUS");
        log.info("═══════════════════════════════════════════════════════════");

        // Client status
        log.info("📡 CLIENT MODE: {}", config.isEnableClient() ? "ENABLED" : "DISABLED");
        if (config.isEnableClient()) {
            log.info("   └─ Remote: {}:{}", remoteCardanoHost, remoteCardanoPort);
            log.info("   └─ Syncing: {}", isSyncing() ? "YES" : "NO");
            log.info("   └─ Blocks processed: {}", blocksProcessed);
            log.info("   └─ Last slot: {}", lastProcessedSlot);
        }

        // Server status
        log.info("🌐 SERVER MODE: {}", config.isEnableServer() ? "ENABLED" : "DISABLED");
        if (config.isEnableServer()) {
            log.info("   └─ Port: {}", serverPort);
            log.info("   └─ Running: {}", isServerRunning() ? "YES" : "NO");
            log.info("   └─ Protocol magic: {}", protocolMagic);
        }

        // Chain state status
        ChainTip tip = chainState.getTip();
        log.info("💾 CHAIN STATE: {}", tip != null ? "HAS DATA" : "EMPTY");
        if (tip != null) {
            log.info("   └─ Tip slot: {}", tip.getSlot());
            log.info("   └─ Tip block: {}", tip.getBlockNumber());
            log.info("   └─ Storage: {}", config.isUseRocksDB() ? "RocksDB" : "InMemory");
        } else {
            log.warn("   └─ ⚠️  NO BLOCKCHAIN DATA - Server cannot serve requests");
        }

        // Overall status
        boolean canServeClients = tip != null && isServerRunning();
        log.info("🎯 READY TO SERVE: {}", canServeClients ? "YES ✅" : "NO ❌");

        if (!canServeClients) {
            log.warn("⚠️  DIAGNOSTIC: Real Cardano nodes will not connect because:");
            if (tip == null) {
                log.warn("   • Server has no blockchain data to serve");
                log.warn("   • Wait for client sync to download blocks first");
            }
            if (!isServerRunning()) {
                log.warn("   • Server is not running properly");
            }
        }

        log.info("═══════════════════════════════════════════════════════════");
    }

    // Override conflicting method from both interfaces
    @Override
    public void intersactFound(Tip tip, Point point) {
        // Implementation for both interfaces
        log.info("Intersection found at point: {}", point);
    }

    // ChainSyncAgentListener methods with originalHeaderBytes for proper storage
    @Override
    public void rollforward(Tip tip, BlockHeader blockHeader, byte[] originalHeaderBytes) {
        headersReceived++;
        lastProcessedSlot = Math.max(lastProcessedSlot, blockHeader.getHeaderBody().getSlot());

        remoteTip = tip;

        if (originalHeaderBytes != null && originalHeaderBytes.length > 0) {
            try {
                // Store header immediately when received from ChainSync
                chainState.storeBlockHeader(
                        HexUtil.decodeHexString(blockHeader.getHeaderBody().getBlockHash()),
                        blockHeader.getHeaderBody().getBlockNumber(),
                        blockHeader.getHeaderBody().getSlot(),
                        originalHeaderBytes
                );

                if (log.isDebugEnabled()) {
                    log.debug("Successfully stored Shelley+ block header: slot={}, hash={}",
                            blockHeader.getHeaderBody().getSlot(),
                            blockHeader.getHeaderBody().getBlockHash());
                }

                // Log header progress in pipelined mode
                if (isPipelinedMode) {
                    if (headersReceived % 100 == 0) {
                        log.info("ChainSync: Processed {} headers, current block: {}, current slot: {} (Shelley+)",
                                headersReceived, blockHeader.getHeaderBody().getBlockNumber(), blockHeader.getHeaderBody().getSlot());
                    }
                } else {
                    // Legacy mode logging
                    if (headersReceived % 100 == 0) {
                        log.info("ChainSync: Processed {} headers, current block: {}, current slot: {} (Shelley+)",
                                headersReceived, blockHeader.getHeaderBody().getBlockNumber(), blockHeader.getHeaderBody().getSlot());
                    }
                }

                if (log.isDebugEnabled()) {
                    log.debug("Stored Shelley+ header: slot={}, hash={}",
                            blockHeader.getHeaderBody().getSlot(),
                            blockHeader.getHeaderBody().getBlockHash());
                }

            } catch (Exception e) {
                log.error("Error storing Shelley+ block header from original bytes", e);
                throw new RuntimeException("Failed to store Shelley+ block header", e);
            }
        } else {
            // Fall back to existing behavior if originalHeaderBytes not available
            log.warn("No original header bytes available for Shelley+ block: {}", blockHeader.getHeaderBody().getBlockHash());
            throw new RuntimeException("Original header bytes not available for Shelley+ block: " + blockHeader.getHeaderBody().getBlockHash());
        }
    }

    @Override
    public void rollforwardByronEra(Tip tip, ByronBlockHead byronBlockHead, byte[] originalHeaderBytes) {
        headersReceived++;
        lastProcessedSlot = Math.max(lastProcessedSlot, byronBlockHead.getConsensusData().getAbsoluteSlot());

        if (originalHeaderBytes != null && originalHeaderBytes.length > 0) {
            try {
                // Store Byron header immediately when received from ChainSync
                chainState.storeBlockHeader(
                        HexUtil.decodeHexString(byronBlockHead.getBlockHash()),
                        byronBlockHead.getConsensusData().getDifficulty().longValue(),
                        byronBlockHead.getConsensusData().getAbsoluteSlot(),
                        originalHeaderBytes
                );

                // Log header progress in pipelined mode
                if (isPipelinedMode) {
                    if (headersReceived % 100 == 0) {
                        log.info("📄 Headers: {} received, current slot: {} (Byron)",
                                headersReceived, byronBlockHead.getConsensusData().getAbsoluteSlot());
                    }
                } else {
                    // Legacy mode logging
                    if (headersReceived % 100 == 0) {
                        log.info("BlockFetch: Processed {} headers, current slot: {} (Byron)",
                                headersReceived, byronBlockHead.getConsensusData().getAbsoluteSlot());
                    }
                }

                if (log.isDebugEnabled()) {
                    log.debug("Stored Byron header: slot={}, hash={}",
                            byronBlockHead.getConsensusData().getAbsoluteSlot(),
                            byronBlockHead.getBlockHash());
                }

            } catch (Exception e) {
                log.error("Error storing Byron block header from original bytes", e);
                throw new RuntimeException("Failed to store Byron block header", e);
            }
        } else {
            // Fall back to existing behavior if originalHeaderBytes not available
            log.warn("No original header bytes available for Byron block: {}", byronBlockHead.getBlockHash());
            throw new RuntimeException("Original header bytes not available for Byron block: " + byronBlockHead.getBlockHash());
        }
    }

    @Override
    public void rollforwardByronEra(Tip tip, ByronEbHead byronEbHead, byte[] originalHeaderBytes) {
        headersReceived++;
        lastProcessedSlot = Math.max(lastProcessedSlot, byronEbHead.getConsensusData().getAbsoluteSlot());

        if (originalHeaderBytes != null && originalHeaderBytes.length > 0) {
            try {
                // Store Byron EB header immediately when received from ChainSync
                chainState.storeBlockHeader(
                        HexUtil.decodeHexString(byronEbHead.getBlockHash()),
                        byronEbHead.getConsensusData().getDifficulty().longValue(),
                        byronEbHead.getConsensusData().getAbsoluteSlot(),
                        originalHeaderBytes
                );

                // Log header progress in pipelined mode
                if (isPipelinedMode) {
                    if (headersReceived % 100 == 0) {
                        log.info("📄 Headers: {} received, current slot: {} (Byron EB)",
                                headersReceived, byronEbHead.getConsensusData().getAbsoluteSlot());
                    }
                }

                if (log.isDebugEnabled()) {
                    log.debug("Stored Byron EB header: slot={}, hash={}",
                            byronEbHead.getConsensusData().getAbsoluteSlot(),
                            byronEbHead.getBlockHash());
                }

            } catch (Exception e) {
                log.error("Error storing Byron EB block header from original bytes", e);
                throw new RuntimeException("Failed to store Byron EB block header", e);
            }
        } else {
            // Fall back to existing behavior if originalHeaderBytes not available
            log.warn("No original header bytes available for Byron EB block: {}", byronEbHead.getBlockHash());
            throw new RuntimeException("Original header bytes not available for Byron EB block: " + byronEbHead.getBlockHash());
        }
    }
}
