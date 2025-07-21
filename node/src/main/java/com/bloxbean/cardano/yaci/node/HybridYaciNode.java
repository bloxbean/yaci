package com.bloxbean.cardano.yaci.node;

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
import com.bloxbean.cardano.yaci.node.chain.InMemoryChainState;
import com.bloxbean.cardano.yaci.node.chain.DirectRocksDBChainState;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.List;
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
public class HybridYaciNode implements BlockChainDataListener, ChainSyncAgentListener {

    // Configuration
    private final HybridNodeConfig config;

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

    public HybridYaciNode(HybridNodeConfig config) {
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
                log.debug("Error in selective body fetch strategy, defaulting to fetch: {}", e.getMessage());
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

            // Determine starting point for sync
            Point startPoint = determineStartPoint(localTip);
            log.info("Starting pipelined sync from point: {}", startPoint);

            // Find remote tip to understand sync scope
            // TEMPORARILY DISABLED: TipFinder with Point.ORIGIN might cause rollbacks
//             if (remoteTip == null) {
//                 TipFinder tipFinder = new TipFinder(remoteCardanoHost, remoteCardanoPort,
//                         Point.ORIGIN, protocolMagic);
//                 remoteTip = tipFinder.find().block(Duration.ofSeconds(5));
//                 log.info("Remote tip: slot={}, block={}",
//                     remoteTip.getPoint().getSlot(), remoteTip.getBlock());
//             }

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
//
//        // Estimate if local tip is old by comparing with current time
//        // Preprod has 1 slot per second, so we can estimate remote tip slot
//        long currentTimeSeconds = System.currentTimeMillis() / 1000;
//        // Preprod epoch started around 2022-02-01, estimate slots from then
//        long estimatedCurrentSlot = currentTimeSeconds - 1643673600; // Rough estimate
//
//        long slotDifference = Math.max(0, estimatedCurrentSlot - localTip.getSlot());
//
//        log.info("Local tip slot: {}, estimated current slot: {}, difference: {} slots",
//                localTip.getSlot(), estimatedCurrentSlot, slotDifference);

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

        // Check if we've caught up to the remote tip (BlockFetch complete)
        checkSyncProgress();

//        log.info("ChainSync: Block #{} at slot {} ({})", blockNumber, lastProcessedSlot, era);
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

        // Normal rollback (small rollbacks are expected)
        chainState.rollbackTo(rollbackSlot);
        log.info("Rollback to slot: {} (from tip: {})", rollbackSlot,
            localTip != null ? String.format("slot=%d, block=%d", localTip.getSlot(), localTip.getBlockNumber()) : "null");
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
    private void storeByronBlock(ByronMainBlock byronBlock) {
        try {
            byte[] blockHash = HexUtil.decodeHexString(byronBlock.getHeader().getBlockHash());
            long blockNumber = byronBlock.getHeader().getConsensusData().getDifficulty().longValue();
            long slot = byronBlock.getHeader().getConsensusData().getAbsoluteSlot();
            byte[] blockCbor = HexUtil.decodeHexString(byronBlock.getCbor());

            // Store complete block (body received from BlockFetch)
            chainState.storeBlock(blockHash, blockNumber, slot, blockCbor);

            // Also store header separately if not already stored from ChainSync
            // This ensures we have header-only access for serving downstream clients
            try {
                byte[] existingHeader = chainState.getBlockHeader(blockHash);
                if (existingHeader == null) {
                    // Header not stored yet via ChainSync, store it now
                    chainState.storeBlockHeader(blockHash, blockCbor); // Use full block CBOR for now
                    log.debug("Stored Byron header from BlockFetch: slot={}", slot);
                }
            } catch (Exception headerException) {
                log.debug("Could not check/store Byron header separately: {}", headerException.getMessage());
            }

        } catch (Exception e) {
            log.error("Error storing Byron block", e);
        }
    }

    private void storeByronEbBlock(ByronEbBlock byronEbBlock) {
        try {
            byte[] blockHash = HexUtil.decodeHexString(byronEbBlock.getHeader().getBlockHash());
            long blockNumber = byronEbBlock.getHeader().getConsensusData().getDifficulty().longValue();
            long slot = byronEbBlock.getHeader().getConsensusData().getAbsoluteSlot();
            byte[] blockCbor = HexUtil.decodeHexString(byronEbBlock.getCbor());

            // Store complete block (body received from BlockFetch)
            chainState.storeBlock(blockHash, blockNumber, slot, blockCbor);

            // Also store header separately if not already stored from ChainSync
            // This ensures we have header-only access for serving downstream clients
            try {
                byte[] existingHeader = chainState.getBlockHeader(blockHash);
                if (existingHeader == null) {
                    // Header not stored yet via ChainSync, store it now
                    chainState.storeBlockHeader(blockHash, blockCbor); // Use full block CBOR for now
                    log.debug("Stored Byron EB header from BlockFetch: slot={}", slot);
                }
            } catch (Exception headerException) {
                log.debug("Could not check/store Byron EB header separately: {}", headerException.getMessage());
            }

        } catch (Exception e) {
            log.error("Error storing Byron EB block", e);
        }
    }

    private void storeShelleyBlock(Block block) {
        try {
            byte[] blockHash = HexUtil.decodeHexString(block.getHeader().getHeaderBody().getBlockHash());
            long blockNumber = block.getHeader().getHeaderBody().getBlockNumber();
            long slot = block.getHeader().getHeaderBody().getSlot();
            byte[] blockCbor = HexUtil.decodeHexString(block.getCbor());

            // Store complete block (body received from BlockFetch)
            chainState.storeBlock(blockHash, blockNumber, slot, blockCbor);

            // Also store header separately if not already stored from ChainSync
            // This ensures we have header-only access for serving downstream clients
            try {
                byte[] existingHeader = chainState.getBlockHeader(blockHash);
                if (existingHeader == null) {
                    // Header not stored yet via ChainSync, store it now
                    chainState.storeBlockHeader(blockHash, blockCbor); // Use full block CBOR for now
                    log.debug("Stored Shelley+ header from BlockFetch: slot={}", slot);
                }
            } catch (Exception headerException) {
                log.debug("Could not check/store Shelley+ header separately: {}", headerException.getMessage());
            }

        } catch (Exception e) {
            log.error("Error storing Shelley+ block", e);
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

    public HybridNodeConfig getConfig() {
        return config;
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
        log.debug("Intersection found at point: {}", point);
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
                    originalHeaderBytes
                );

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

                log.debug("Stored Shelley+ header: slot={}, hash={}",
                    blockHeader.getHeaderBody().getSlot(),
                    blockHeader.getHeaderBody().getBlockHash());

            } catch (Exception e) {
                log.error("Error storing Shelley+ block header from original bytes", e);
            }
        } else {
            // Fall back to existing behavior if originalHeaderBytes not available
            log.warn("No original header bytes available for Shelley+ block: {}", blockHeader.getHeaderBody().getBlockHash());
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

                log.debug("Stored Byron header: slot={}, hash={}",
                    byronBlockHead.getConsensusData().getAbsoluteSlot(),
                    byronBlockHead.getBlockHash());

            } catch (Exception e) {
                log.error("Error storing Byron block header from original bytes", e);
            }
        } else {
            // Fall back to existing behavior if originalHeaderBytes not available
            log.warn("No original header bytes available for Byron block: {}", byronBlockHead.getBlockHash());
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
                    originalHeaderBytes
                );

                // Log header progress in pipelined mode
                if (isPipelinedMode) {
                    if (headersReceived % 100 == 0) {
                        log.info("📄 Headers: {} received, current slot: {} (Byron EB)",
                            headersReceived, byronEbHead.getConsensusData().getAbsoluteSlot());
                    }
                }

                log.debug("Stored Byron EB header: slot={}, hash={}",
                    byronEbHead.getConsensusData().getAbsoluteSlot(),
                    byronEbHead.getBlockHash());

            } catch (Exception e) {
                log.error("Error storing Byron EB block header from original bytes", e);
            }
        } else {
            // Fall back to existing behavior if originalHeaderBytes not available
            log.warn("No original header bytes available for Byron EB block: {}", byronEbHead.getBlockHash());
        }
    }
}
