package com.bloxbean.cardano.yaci.node.runtime;

import com.bloxbean.cardano.yaci.core.config.YaciConfig;
import com.bloxbean.cardano.yaci.core.network.server.NodeServer;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.events.api.support.AnnotationListenerRegistrar;
import com.bloxbean.cardano.yaci.helper.*;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.node.api.NodeAPI;
import com.bloxbean.cardano.yaci.node.api.SyncPhase;
import com.bloxbean.cardano.yaci.node.api.config.YaciNodeConfig;
import com.bloxbean.cardano.yaci.node.api.listener.NodeEventListener;
import com.bloxbean.cardano.yaci.node.api.config.RuntimeOptions;
import com.bloxbean.cardano.yaci.events.api.config.EventsOptions;
import com.bloxbean.cardano.yaci.node.api.model.NodeStatus;
import com.bloxbean.cardano.yaci.node.runtime.chain.InMemoryChainState;
import com.bloxbean.cardano.yaci.node.runtime.chain.DirectRocksDBChainState;
import com.bloxbean.cardano.yaci.node.runtime.chain.MemPool;
import com.bloxbean.cardano.yaci.node.runtime.chain.DefaultMemPool;
import com.bloxbean.cardano.yaci.node.runtime.handlers.YaciTxSubmissionHandler;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.TxSubmissionConfig;
import lombok.extern.slf4j.Slf4j;
import com.bloxbean.cardano.yaci.events.api.*;
import com.bloxbean.cardano.yaci.events.impl.SimpleEventBus;
import com.bloxbean.cardano.yaci.events.impl.NoopEventBus;
import com.bloxbean.cardano.yaci.node.runtime.events.NodeStartedEvent;
import com.bloxbean.cardano.yaci.node.runtime.plugins.PluginManager;
import com.bloxbean.cardano.yaci.node.runtime.utxo.ClassicUtxoStore;
import com.bloxbean.cardano.yaci.node.runtime.utxo.PruneService;
import com.bloxbean.cardano.yaci.node.runtime.utxo.UtxoEventHandler;
import com.bloxbean.cardano.yaci.node.api.utxo.UtxoState;

import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Yaci Node - Acts as both client and server
 * <p>
 * CLIENT MODE: Syncs with real Cardano nodes (preprod relay nodes)
 * SERVER MODE: Serves other Yaci clients with blockchain data
 * <p>
 * This enables Yaci to act as a bridge/relay node
 */
@Slf4j
public class YaciNode implements NodeAPI {

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

    // Pipeline managers
    private HeaderSyncManager headerSyncManager;
    private BodyFetchManager bodyFetchManager;

    // Remote tip info for sync strategy
    private com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip remoteTip;

    // Server components (for serving other clients)
    private NodeServer nodeServer;
    private final int serverPort;

    // MemPool for transaction handling
    private final MemPool memPool;

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

    // Events & Plugins
    private final RuntimeOptions runtimeOptions;
    private final EventBus eventBus;
    private PluginManager pluginManager;
    private ClassicUtxoStore utxoStore;
    private PruneService utxoPruneService;
    private UtxoEventHandler utxoEventHandler;
    private com.bloxbean.cardano.yaci.node.runtime.utxo.UtxoEventHandlerAsync utxoEventHandlerAsync;
    private java.util.concurrent.ScheduledFuture<?> utxoLagTask;

    public YaciNode(YaciNodeConfig config) {
        this(config, RuntimeOptions.defaults());
    }

    public YaciNode(YaciNodeConfig config, RuntimeOptions options) {
        this.config = config;
        this.runtimeOptions = options != null ? options : RuntimeOptions.defaults();
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

        // Initialize MemPool for transaction handling
        this.memPool = new DefaultMemPool();

        // Configure Yaci
        YaciConfig.INSTANCE.setReturnBlockCbor(true);
        YaciConfig.INSTANCE.setReturnTxBodyCbor(true);

        // Initialize pipeline configuration
        this.pipelineConfig = createPipelineConfig();

        log.info("Yaci Node initialized");
        log.info("Remote: {}:{} (magic: {})", remoteCardanoHost, remoteCardanoPort, protocolMagic);
        log.info("Server port: {}", serverPort);
        log.info("Storage: {}", config.isUseRocksDB() ? "RocksDB" : "InMemory");
        log.info("Pipeline config: {}", pipelineConfig);

        // Event bus
        EventsOptions ev = this.runtimeOptions.events();
        this.eventBus = ev.enabled() ? new SimpleEventBus() : new NoopEventBus();

        // Initialize plugins (discovery is deferred to start())
        if (this.runtimeOptions.plugins().enabled()) {
            pluginManager = new PluginManager(eventBus, scheduler, this.runtimeOptions.plugins().config(), Thread.currentThread().getContextClassLoader());
        }

        // Phase 3: Initialize UTXO store (classic) if enabled and RocksDB is used
        try {
            Object enabledOpt = this.runtimeOptions.globals().get("yaci.node.utxo.enabled");
            boolean utxoEnabled = enabledOpt instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(enabledOpt));
            Object storeOpt = this.runtimeOptions.globals().getOrDefault("yaci.node.utxo.store", "classic");
            String storeName = String.valueOf(storeOpt);
            if (utxoEnabled && "classic".equalsIgnoreCase(storeName) && (chainState instanceof DirectRocksDBChainState rocks)) {
                this.utxoStore = new ClassicUtxoStore(rocks, log, this.runtimeOptions.globals());
                // Reconcile UTXO with chainstate before subscribing and starting prune
                try {
                    this.utxoStore.reconcile(rocks);
                    log.info("UTXO reconciliation complete at startup");
                } catch (Throwable t) {
                    log.warn("UTXO reconciliation error: {}", t.toString());
                }
                boolean applyAsync = false;
                Object asyncOpt = this.runtimeOptions.globals().get("yaci.node.utxo.applyAsync");
                if (asyncOpt instanceof Boolean b) applyAsync = b; else if (asyncOpt != null) try { applyAsync = Boolean.parseBoolean(String.valueOf(asyncOpt)); } catch (Exception ignored) {}
                if (applyAsync) {
                    this.utxoEventHandlerAsync = new com.bloxbean.cardano.yaci.node.runtime.utxo.UtxoEventHandlerAsync(eventBus, this.utxoStore);
                    log.info("ClassicUtxoStore initialized; UtxoEventHandlerAsync registered with EventBus (applyAsync=true)");
                } else {
                    this.utxoEventHandler = new UtxoEventHandler(eventBus, this.utxoStore);
                    log.info("ClassicUtxoStore initialized; UtxoEventHandler registered with EventBus");
                }
                // Start prune service on virtual-thread scheduler
                long intervalSec = 5L;
                Object po = this.runtimeOptions.globals().get("yaci.node.utxo.prune.schedule.seconds");
                if (po instanceof Number n) intervalSec = Math.max(1L, n.longValue());
                else if (po != null) try { intervalSec = Math.max(1L, Long.parseLong(String.valueOf(po))); } catch (Exception ignored) {}
                this.utxoPruneService = new PruneService(this.utxoStore, intervalSec * 1000);
                this.utxoPruneService.start();
                log.info("UTXO prune service started (interval={}s)", intervalSec);

                // Schedule UTXO lag metric logging
                long lagLogSec = 10L;
                Object lagObj = this.runtimeOptions.globals().get("yaci.node.utxo.metrics.lag.logSeconds");
                if (lagObj instanceof Number n) lagLogSec = Math.max(1L, n.longValue());
                else if (lagObj != null) try { lagLogSec = Math.max(1L, Long.parseLong(String.valueOf(lagObj))); } catch (Exception ignored) {}
                final long failIfAbove = parseLong(this.runtimeOptions.globals().get("yaci.node.utxo.lag.failIfAbove"), -1L);
                this.utxoLagTask = scheduler.scheduleAtFixedRate(() -> {
                    try {
                        long lastApplied = this.utxoStore.readLastAppliedBlock();
                        var tip = chainState.getTip();
                        long tipBlock = tip != null ? tip.getBlockNumber() : 0L;
                        long lag = Math.max(0L, tipBlock - lastApplied);

                        if (lag > 0)
                            log.info("metric utxo.lag.blocks={}", lag);

                        if (failIfAbove > 0 && lag > failIfAbove) {
                            log.warn("UTXO lag {} blocks exceeds configured threshold {}", lag, failIfAbove);
                        }
                    } catch (Throwable ignored) {}
                }, lagLogSec, lagLogSec, java.util.concurrent.TimeUnit.SECONDS);
            } else {
                log.info("UTXO store not initialized (enabled={}, store={}, rocksdb={})", utxoEnabled, storeName, (chainState instanceof DirectRocksDBChainState));
            }
        } catch (Throwable t) {
            log.warn("Failed to initialize UTXO store: {}", t.toString());
        }
    }

    @Override
    public UtxoState getUtxoState() {
        return utxoStore;
    }

    /**
     * Create pipeline configuration using values from NodeConfig
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
     * Start the node (both client and server)
     */
    public void start() {
        if (isRunning.compareAndSet(false, true)) {
            log.info("Starting Yaci Node...");

            // Start server first
            if (config.isEnableServer()) {
                startServer();
            }

            // Validate chain state before starting sync
            if (config.isEnableClient()) {
                validateChainState();
                startClientSync();
            }

            log.info("Yaci Node started successfully");

            // Print startup status
            printStartupStatus();

            // Start plugins and publish a startup event
            if (pluginManager != null && this.runtimeOptions.plugins().enabled()) {
                try {
                    pluginManager.discoverAndInit();
                    pluginManager.startAll();
                } catch (Exception e) {
                    log.warn("Plugin manager init/start failed: {}", e.toString(), e);
                }
            }

            EventMetadata meta = EventMetadata.builder().origin("node-runtime").build();
            eventBus.publish(new NodeStartedEvent(System.currentTimeMillis()), meta, PublishOptions.builder().build());
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
                log.error("❌ Yaci Node must sync some blockchain data first before serving");
            }

            // Create TxSubmission handler for transaction processing
            YaciTxSubmissionHandler txSubmissionHandler = new YaciTxSubmissionHandler(memPool, eventBus);

            // Create TxSubmission configuration for periodic requests
            TxSubmissionConfig txSubmissionConfig = TxSubmissionConfig.builder()
                    .batchSize(10)       // 10 transactions per request
                    .useBlockingMode(false)
                    .build();

            nodeServer = new NodeServer(serverPort,
                    N2NVersionTableConstant.v11AndAbove(protocolMagic, false, 0, false),
                    chainState,
                    txSubmissionHandler,
                    txSubmissionConfig);

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

            // Agent will self-manage periodic requests when sessions are established

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

            // Get local tips to determine sync strategy
            // Use header_tip as primary reference for restart efficiency
            ChainTip headerTip = chainState.getHeaderTip();
            ChainTip bodyTip = chainState.getTip();

            // Use header_tip if available, fall back to body_tip
            ChainTip localTip = headerTip != null ? headerTip : bodyTip;

            log.info("Local header_tip: {}, body_tip: {}, using: {} for sync",
                     headerTip, bodyTip, localTip != null ? "slot " + localTip.getSlot() : "genesis");

            // Initialize last known tip
            lastKnownChainTip = localTip;

            // Determine starting point for sync (will use header_tip when available)
            Point startPoint = determineStartPoint(localTip);
            log.info("Starting pipelined sync from point: {}", startPoint);

            // Find remote tip to understand sync scope
            TipFinder tipFinder = new TipFinder(remoteCardanoHost, remoteCardanoPort, startPoint, protocolMagic);
            remoteTip = tipFinder.find()
                    .doFinally(signalType -> tipFinder.shutdown())
                    .block(Duration.ofSeconds(5));

            // Create PeerClient
            if (peerClient == null) {
                peerClient = new PeerClient(remoteCardanoHost, remoteCardanoPort, protocolMagic, startPoint);
                // Note: Connection will be established in pipeline or sequential mode below
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

        // Initialize pipeline managers
        initializePipelineManagers();

        // Create composite listener that delegates to both managers
        PipelineDataListener pipelineListener = new PipelineDataListener(
                headerSyncManager,
                bodyFetchManager,
                this  // Pass YaciNode reference for rollback coordination
        );

        // Connect using existing PeerClient.connect() method with pipeline listener
        peerClient.connect(pipelineListener, null); // TxSubmission handled separately if needed

        // Start header-only sync
        peerClient.startHeaderSync(startPoint, true); // Enable pipelining for headers
        log.info("🔗 ==> Header sync started with pipelining enabled");

        // Start body fetch manager monitoring
        bodyFetchManager.start();
        log.info("📦 ==> Body fetch manager started for range-based fetching");

        log.info("🚀 Pipeline startup complete - HeaderSync and BodyFetch active");
    }

    /**
     * Initialize HeaderSyncManager and BodyFetchManager for pipeline mode.
     */
    private void initializePipelineManagers() {
        // Shared context to propagate latest network tip from headers to bodies
        SyncTipContext syncTipContext = new SyncTipContext();
        if (headerSyncManager != null) {
            // Reset existing managers
            headerSyncManager.resetMetrics();
        } else {
            // Create new HeaderSyncManager
            headerSyncManager = new HeaderSyncManager(peerClient, chainState, 50000, syncTipContext);
            log.info("📋 HeaderSyncManager created");
        }

        if (bodyFetchManager != null) {
            // Stop and reset existing manager
            if (bodyFetchManager.isRunning()) {
                bodyFetchManager.stop();
            }
            bodyFetchManager.resetMetrics();
        } else {
            // Create new BodyFetchManager with appropriate configuration
            // Use slot-based threshold since gaps are measured in slots, not blocks
            // 100 slots ≈ 1.67 minutes at 20s/slot (reasonable for body fetching)
            long gapThreshold = pipelineConfig != null ?
                    Math.max(pipelineConfig.getBodyBatchSize() / 10, 100) : 100; // Slot-based threshold
            int maxBatchSize = pipelineConfig != null ?
                    pipelineConfig.getBodyBatchSize() : 500;

            maxBatchSize = 5000;

            bodyFetchManager = new BodyFetchManager(
                    peerClient,
                    chainState,
                    eventBus,
                    gapThreshold,
                    maxBatchSize,
                    500, // 500ms monitoring interval
                    1000,  // tipProximityThreshold - consider "at tip" when within 1000 slots (~16 minutes)
                    syncTipContext
            );
            log.info("📦 BodyFetchManager created with gapThreshold={}, maxBatchSize={}",
                    gapThreshold, maxBatchSize);
        }

        log.info("🔗 Pipeline managers initialized and ready");
        log.info("ℹ️  HeaderSyncManager will receive headers through ChainSync protocol");
        if (bodyFetchManager != null) {
            log.info("ℹ️  BodyFetchManager will monitor for gaps and fetch ranges automatically");
        }
    }

    /**
     * Start sequential client sync (traditional mode for performance comparison)
     */
    private void startSequentialClientSync(Point startPoint) {
        log.info("📦 ==> SEQUENTIAL SYNC: Using traditional header+body sync");
        isInitialSyncComplete = false;

        // Create composite listener that delegates to both managers
        PipelineDataListener pipelineListener = new PipelineDataListener(
                headerSyncManager,
                bodyFetchManager,
                this  // Pass YaciNode reference for rollback coordination
        );

        // Connect using existing PeerClient.connect() method with pipeline listener
        peerClient.connect(pipelineListener, null); // TxSubmission handled separately if needed

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
                log.info("🚀 ==> Yaci Node is now fully synchronized and serving clients");
                log.info("🚀 ==> Will now log every block as it arrives in real-time");
                // Reflect phase change
                var prev = syncPhase;
                updateSyncProgress();
                if (prev != syncPhase) {
                    EventMetadata meta = EventMetadata.builder().origin("node-runtime").build();
                    eventBus.publish(new com.bloxbean.cardano.yaci.node.runtime.events.SyncStatusChangedEvent(prev, syncPhase), meta, PublishOptions.builder().build());
                }
            }
        }
    }

    /**
     * Stop the Yaci node
     */
    public void stop() {
        if (isRunning.compareAndSet(true, false)) {
            log.info("Stopping Yaci Node...");

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

            // Stop UTXO prune service
            try { if (utxoPruneService != null) utxoPruneService.close(); } catch (Exception ignored) {}
            try { if (utxoLagTask != null) utxoLagTask.cancel(true); } catch (Exception ignored) {}

            // Stop plugins and close event bus
            try { if (pluginManager != null) pluginManager.close(); } catch (Exception ignored) {}
            try { if (utxoEventHandler != null) utxoEventHandler.close(); } catch (Exception ignored) {}
            try { if (utxoEventHandlerAsync != null) utxoEventHandlerAsync.close(); } catch (Exception ignored) {}
            try { eventBus.close(); } catch (Exception ignored) {}

            log.info("Yaci Node stopped");
        }
    }

    // Legacy BlockChainDataListener methods - now handled by pipeline managers
    // Kept for backward compatibility but commented out
    /*
    @Override
    public void onByronBlock(ByronMainBlock byronBlock) {
        // In pipeline mode, delegate to BodyFetchManager for block storage
        if (isPipelinedMode && bodyFetchManager != null) {
            bodyFetchManager.onByronBlock(byronBlock);
        } else {
            // Sequential mode - handle normally
            storeByronBlock(byronBlock);
        }

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
    */

    /*
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
    */

    /*
    @Override
    public void onBlock(Era era, Block block, List<Transaction> transactions) {
        // In pipeline mode, delegate to BodyFetchManager for block storage
        if (isPipelinedMode && bodyFetchManager != null) {
            bodyFetchManager.onBlock(era, block, transactions);
        } else {
            // Sequential mode - handle normally
            storeShelleyBlock(block);
        }

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
    */

    // Rollback handling - coordinates between managers and handles server notifications
    public void handleRollback(Point point) {
        var localTip = chainState.getTip();
        long rollbackSlot = point.getSlot();

        // In pipeline mode, pause BodyFetchManager during rollback
        if (isPipelinedMode && bodyFetchManager != null) {
            bodyFetchManager.pause();
            log.info("⏸️ BodyFetchManager paused for rollback to slot {}", rollbackSlot);
        }

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

        // Publish rollback event
        try {
            EventMetadata meta = EventMetadata.builder().origin("node-runtime").build();
            eventBus.publish(new com.bloxbean.cardano.yaci.node.runtime.events.RollbackEvent(point, isReal), meta, PublishOptions.builder().build());
        } catch (Exception ex) {
            log.debug("RollbackEvent publish failed: {}", ex.toString());
        }

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

        // Post-rollback integrity check and opportunistic recovery
        attemptCorruptionRecovery("post-rollback");

        // Always resume BodyFetchManager after rollback - let it handle its own gap detection
        if (isPipelinedMode && bodyFetchManager != null) {
            bodyFetchManager.resume();
            log.info("▶️ BodyFetchManager resumed after rollback - will detect and handle gaps automatically");
        }
    }

    /**
     * Opportunistically validate and recover chainstate outside of startup.
     * Safe to call after rollback/reconnection.
     */
    private void attemptCorruptionRecovery(String context) {
        try {
            if (!(chainState instanceof DirectRocksDBChainState rocks)) return;

            if (rocks.detectCorruption()) {
                log.warn("🚨 Corruption detected during {} - attempting recovery", context);
                rocks.recoverFromCorruption();
                log.info("✅ Recovery completed during {} - continuing sync", context);
            } else {
                log.debug("No corruption detected during {} check", context);
            }
        } catch (Exception e) {
            log.warn("Recovery attempt during {} failed: {}", context, e.toString());
        }
    }

    /*
    @Override
    public void batchDone() {
        if (isBulkBatchSync) {
            isBulkBatchSync = false; // Reset bulk sync flag
            log.info("Batch sync complete - activate ChainSync mode");
            startClientSync();
        }
    }
    */


    /*
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
    */

    /*
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
    */

    // Private helper methods

    // Storage helper methods - now handled by pipeline managers
    /*
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
    */

    /*
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
    */

    /*
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
    */

    /*
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
    */

    /*
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
    */

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
     * Update sync phase based on sync progress
     */
    public void updateSyncProgress() {
        if (syncPhase == SyncPhase.INITIAL_SYNC && isInitialSyncComplete) {
            syncPhase = SyncPhase.STEADY_STATE;
            log.info("Transitioned to STEADY_STATE sync phase");

            // Update BodyFetchManager sync phase and resume if needed
            if (isPipelinedMode && bodyFetchManager != null) {
                bodyFetchManager.setSyncPhase(SyncPhase.STEADY_STATE);
                if (bodyFetchManager.isPaused()) {
                    bodyFetchManager.resume();
                    log.info("▶️ BodyFetchManager resumed after transition to STEADY_STATE");
                }
            }
        }
    }

    /**
     * Notify the server about new block availability when blocks are stored in pipeline mode.
     * This is called by PipelineDataListener after blocks are successfully stored by BodyFetchManager.
     * Only notifies during STEADY_STATE (at tip) to avoid excessive notifications during initial sync.
     */
    public void notifyServerNewBlockStored() {
        // Only notify server if we're in real-time mode (STEADY_STATE) and server is running
        // This avoids excessive notifications during initial sync when processing thousands of blocks
        if (syncPhase == SyncPhase.STEADY_STATE && isServerRunning.get() && nodeServer != null) {
            try {
                nodeServer.notifyNewDataAvailable();
                log.debug("Notified server agents about new block availability (at tip)");
            } catch (Exception e) {
                log.warn("Error notifying server agents about new block", e);
            }
        }
    }

    /**
     * Resume BodyFetchManager when headers start flowing after intersection.
     * This provides immediate resume instead of waiting for the 30s timeout.
     */
    public void resumeBodyFetchOnHeaderFlow() {
        // Only resume during INTERSECT_PHASE when headers are flowing again
        if (isPipelinedMode && syncPhase == SyncPhase.INTERSECT_PHASE &&
            bodyFetchManager != null && bodyFetchManager.isPaused()) {

            // Choose next phase based on distance to remote tip
            long distance = Long.MAX_VALUE;
            try {
                if (remoteTip != null && remoteTip.getPoint() != null) {
                    distance = Math.max(0, remoteTip.getPoint().getSlot() - lastProcessedSlot);
                }
            } catch (Exception ignored) {}

            long nearTipThreshold = 1000; // slots
            SyncPhase nextPhase = (distance <= nearTipThreshold) ? SyncPhase.STEADY_STATE : SyncPhase.INITIAL_SYNC;

            var prev = syncPhase;
            syncPhase = nextPhase;
            bodyFetchManager.setSyncPhase(nextPhase);
            bodyFetchManager.resume();

            log.info("🏃‍♂️ FAST RESUME: Headers flowing - transitioned to {} (distance to tip: {} slots)",
                    nextPhase, distance == Long.MAX_VALUE ? "unknown" : String.valueOf(distance));
            if (prev != syncPhase) {
                EventMetadata meta = EventMetadata.builder().origin("node-runtime").build();
                eventBus.publish(new com.bloxbean.cardano.yaci.node.runtime.events.SyncStatusChangedEvent(prev, syncPhase), meta, PublishOptions.builder().build());
            }
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
    public boolean recoverChainState() {
        if (isRunning()) {
            throw new IllegalStateException("Cannot recover chain state while node is running. Stop the node first.");
        }

        if (chainState instanceof DirectRocksDBChainState rocksDBChainState) {
            log.info("🔧 Initiating chain state recovery...");

            // First check if recovery is needed
            if (!rocksDBChainState.detectCorruption()) {
                log.info("✅ No corruption detected, recovery not needed");
                return false;
            }

            // Perform recovery
            rocksDBChainState.recoverFromCorruption();
            return true;
        } else {
            log.info("Chain state recovery not supported for in-memory storage");
            return false;
        }
    }

    @Override
    public void registerListeners(Object... listeners) {
        var defaultOption = SubscriptionOptions.builder().build();
        for (Object listener : listeners) {
            AnnotationListenerRegistrar.register(eventBus, listener, defaultOption);
        }
    }

    @Override
    public void registerListener(Object listener, SubscriptionOptions sbOptions) {
        AnnotationListenerRegistrar.register(eventBus, listener, sbOptions);
    }

    /**
     * Validate chain state integrity and attempt automatic recovery if corruption is detected
     */
    private void validateChainState() {
        if (chainState instanceof DirectRocksDBChainState rocksDBChainState) {
            log.info("🔍 Validating chain state integrity...");

            if (rocksDBChainState.detectCorruption()) {
                log.warn("🚨 Chain state corruption detected during startup!");

                // Attempt automatic recovery
                try {
                    log.info("🔧 Attempting automatic recovery...");
                    rocksDBChainState.recoverFromCorruption();
                    log.info("✅ Chain state recovered successfully - sync can proceed");
                } catch (Exception e) {
                    log.error("❌ Automatic recovery failed", e);
                    throw new RuntimeException("Chain state is corrupted and automatic recovery failed. " +
                            "Please manually recover using: curl -X POST http://localhost:8080/api/v1/node/recover", e);
                }
            } else {
                log.info("✅ Chain state integrity validated - no corruption detected");
            }
        } else {
            log.debug("Chain state validation skipped (in-memory storage)");
        }
    }

    public MemPool getMemPool() {
        return memPool;
    }

    @Override
    public NodeStatus getStatus() {
        ChainTip localTip = getLocalTip();
        ChainTip headerTip = chainState.getHeaderTip();

        String statusMessage = "Node is " + (isRunning() ? "running" : "stopped");

        // Add pipeline-specific status if in pipeline mode
        if (isPipelinedMode) {
            statusMessage += " (phase: " + syncPhase.name() + ")";

            // Add header tip information
            if (headerTip != null) {
                // Calculate header-body gap for pipeline monitoring
                long gap = localTip != null ?
                        headerTip.getSlot() - localTip.getSlot() :
                        headerTip.getSlot();

                statusMessage += String.format(" [gap: %d blocks]", gap);
            }

            // Add header metrics if available
            if (headerSyncManager != null) {
                var headerMetrics = headerSyncManager.getHeaderMetrics();
                statusMessage += String.format(" [headers: %d]", headerMetrics.totalHeaders);
            }

            // Add body metrics if available
            if (bodyFetchManager != null) {
                var bodyStatus = bodyFetchManager.getStatus();
                statusMessage += String.format(" [bodies: %d]", bodyStatus.bodiesReceived);
            }
        }

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
                .statusMessage(statusMessage)
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
        log.info("🚀 YACI NODE STARTUP STATUS");
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

    /**
     * Handle intersection found event - transition to INTERSECT_PHASE
     */
    public void onIntersectionFound() {
        syncPhase = SyncPhase.INTERSECT_PHASE;
        log.info("Transitioned to INTERSECT_PHASE - expect rollback to intersection");

        // Update BodyFetchManager sync phase
        if (isPipelinedMode && bodyFetchManager != null) {
            bodyFetchManager.setSyncPhase(SyncPhase.INTERSECT_PHASE);
        }

        // After timeout, exit INTERSECT_PHASE. Choose next phase based on distance to remote tip.
        scheduler.schedule(() -> {
            if (syncPhase == SyncPhase.INTERSECT_PHASE) {
                // Determine distance to remote tip; default to INITIAL_SYNC if unknown/far
                long distance = Long.MAX_VALUE;
                try {
                    if (remoteTip != null && remoteTip.getPoint() != null) {
                        distance = Math.max(0, remoteTip.getPoint().getSlot() - lastProcessedSlot);
                    }
                } catch (Exception ignored) {}

                long nearTipThreshold = 1000; // slots
                SyncPhase nextPhase = (distance <= nearTipThreshold) ? SyncPhase.STEADY_STATE : SyncPhase.INITIAL_SYNC;
                syncPhase = nextPhase;
                log.info("Auto-transitioned to {} after intersection phase timeout (distance to tip: {} slots)", nextPhase, distance == Long.MAX_VALUE ? "unknown" : String.valueOf(distance));

                // Update BodyFetchManager sync phase and resume if needed
                if (isPipelinedMode && bodyFetchManager != null) {
                    bodyFetchManager.setSyncPhase(nextPhase);
                    if (bodyFetchManager.isPaused()) {
                        bodyFetchManager.resume();
                        log.info("▶️ BodyFetchManager resumed after auto-transition to {}", nextPhase);
                    }
                }
            }
        }, rollbackClassificationTimeout, TimeUnit.MILLISECONDS);
    }

    /**
     * If local tip is already close to the remote tip, transition to STEADY_STATE immediately.
     * Invoked on intersection-found with the remote tip info available.
     */
    public void maybeFastTransitionToSteadyState(com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip remoteTip) {
        try {
            if (!isPipelinedMode) return;

            ChainTip localTip = chainState.getTip();
            if (localTip == null || remoteTip == null || remoteTip.getPoint() == null) return;

            long remoteSlot = remoteTip.getPoint().getSlot();
            long distance = Math.max(0, remoteSlot - localTip.getSlot());

            long nearTipThreshold = 1000; // slots
            if (distance <= nearTipThreshold) {
                syncPhase = SyncPhase.STEADY_STATE;
                if (bodyFetchManager != null) {
                    bodyFetchManager.setSyncPhase(SyncPhase.STEADY_STATE);
                    if (bodyFetchManager.isPaused()) bodyFetchManager.resume();
                }
                log.info("⚡ NEAR-TIP FAST PATH: remote-local distance={} slots <= {}, transitioned to STEADY_STATE", distance, nearTipThreshold);
            }
        } catch (Exception e) {
            log.debug("Fast transition near-tip check failed: {}", e.toString());
        }
    }

    // Legacy ChainSyncAgentListener methods - now handled by HeaderSyncManager
    /*
    @Override
    public void rollforward(Tip tip, BlockHeader blockHeader, byte[] originalHeaderBytes) {
        headersReceived++;
        lastProcessedSlot = Math.max(lastProcessedSlot, blockHeader.getHeaderBody().getSlot());

        // In pipeline mode, delegate to HeaderSyncManager for header-only processing
        if (isPipelinedMode && headerSyncManager != null) {
            headerSyncManager.rollforward(tip, blockHeader, originalHeaderBytes);
        }

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
    */

    /*
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
    */

    /*
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
    */

    private static long parseLong(Object obj, long def) {
        if (obj instanceof Number n) return n.longValue();
        if (obj != null) {
            try { return Long.parseLong(String.valueOf(obj)); } catch (Exception ignored) {}
        }
        return def;
    }
}
