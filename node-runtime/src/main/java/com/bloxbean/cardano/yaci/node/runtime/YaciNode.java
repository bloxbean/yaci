package com.bloxbean.cardano.yaci.node.runtime;

import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.yaci.core.common.TxBodyType;
import com.bloxbean.cardano.yaci.core.config.YaciConfig;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.network.server.NodeServer;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.TxSubmissionConfig;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.events.api.*;
import com.bloxbean.cardano.yaci.events.api.config.EventsOptions;
import com.bloxbean.cardano.yaci.events.api.support.AnnotationListenerRegistrar;
import com.bloxbean.cardano.yaci.events.impl.NoopEventBus;
import com.bloxbean.cardano.yaci.events.impl.SimpleEventBus;
import com.bloxbean.cardano.yaci.helper.*;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import com.bloxbean.cardano.yaci.node.api.NodeAPI;
import com.bloxbean.cardano.yaci.node.api.SyncPhase;
import com.bloxbean.cardano.yaci.node.api.config.RuntimeOptions;
import com.bloxbean.cardano.yaci.node.api.config.YaciNodeConfig;
import com.bloxbean.cardano.yaci.node.api.events.TransactionValidateEvent;
import com.bloxbean.cardano.yaci.node.api.listener.NodeEventListener;
import com.bloxbean.cardano.yaci.node.api.model.FundResult;
import com.bloxbean.cardano.yaci.node.api.model.GenesisParameters;
import com.bloxbean.cardano.yaci.node.api.model.NodeStatus;
import com.bloxbean.cardano.yaci.node.api.model.SnapshotInfo;
import com.bloxbean.cardano.yaci.node.api.model.TimeAdvanceResult;
import com.bloxbean.cardano.yaci.node.api.utxo.UtxoState;
import com.bloxbean.cardano.yaci.node.ledgerrules.TransactionEvaluator;
import com.bloxbean.cardano.yaci.node.ledgerrules.TransactionValidator;
import com.bloxbean.cardano.yaci.node.api.bootstrap.BootstrapDataProvider;
import com.bloxbean.cardano.yaci.node.api.bootstrap.BootstrapOutpoint;
import com.bloxbean.cardano.yaci.node.runtime.bootstrap.BootstrapResult;
import com.bloxbean.cardano.yaci.node.runtime.bootstrap.BootstrapService;
import com.bloxbean.cardano.yaci.node.runtime.blockproducer.BlockProducer;
import com.bloxbean.cardano.yaci.node.runtime.blockproducer.GenesisConfig;
import com.bloxbean.cardano.yaci.node.runtime.blockproducer.TransactionEvaluationService;
import com.bloxbean.cardano.yaci.node.runtime.blockproducer.TransactionValidationException;
import com.bloxbean.cardano.yaci.node.runtime.blockproducer.TransactionValidationService;
import com.bloxbean.cardano.yaci.node.api.chain.ChainSelectionStrategy;
import com.bloxbean.cardano.yaci.node.api.config.UpstreamConfig;
import com.bloxbean.cardano.yaci.node.api.events.PeerConnectedEvent;
import com.bloxbean.cardano.yaci.node.runtime.chain.BlockPruner;
import com.bloxbean.cardano.yaci.node.runtime.chain.DefaultMemPool;
import com.bloxbean.cardano.yaci.node.runtime.chain.DefaultMempoolEvictionPolicy;
import com.bloxbean.cardano.yaci.node.runtime.chain.DirectRocksDBChainState;
import com.bloxbean.cardano.yaci.node.runtime.chain.InMemoryChainState;
import com.bloxbean.cardano.yaci.node.runtime.chain.MemPool;
import com.bloxbean.cardano.yaci.node.runtime.chain.MempoolEvictionPolicy;
import com.bloxbean.cardano.yaci.node.runtime.chain.PraosChainSelection;
import com.bloxbean.cardano.yaci.node.runtime.peer.PeerConnection;
import com.bloxbean.cardano.yaci.node.runtime.peer.PeerDiscoveryService;
import com.bloxbean.cardano.yaci.node.runtime.peer.PeerPool;
import com.bloxbean.cardano.yaci.node.runtime.sync.BodyFetchScheduler;
import com.bloxbean.cardano.yaci.node.runtime.sync.HeaderFanIn;
import com.bloxbean.cardano.yaci.node.runtime.sync.MultiPeerHeaderListener;
import com.bloxbean.cardano.yaci.node.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yaci.node.api.events.MemPoolTransactionReceivedEvent;
import com.bloxbean.cardano.yaci.node.api.events.NodeStartedEvent;
import com.bloxbean.cardano.yaci.node.api.events.RollbackEvent;
import com.bloxbean.cardano.yaci.node.api.events.SyncStatusChangedEvent;
import com.bloxbean.cardano.yaci.node.runtime.appmsg.AppMessageMemPool;
import com.bloxbean.cardano.yaci.node.runtime.appmsg.DefaultAppMessageMemPool;
import com.bloxbean.cardano.yaci.node.runtime.appmsg.YaciAppMessageHandler;
import com.bloxbean.cardano.yaci.node.runtime.appmsg.auth.OpenAuthenticator;
import com.bloxbean.cardano.yaci.node.runtime.appmsg.auth.PermissionedAuthenticator;
import com.bloxbean.cardano.yaci.node.api.appmsg.MessageAuthenticator;
import com.bloxbean.cardano.yaci.core.network.server.AgentFactory;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgSubmissionAgent;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgSubmissionConfig;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgSubmissionServerAgent;
import com.bloxbean.cardano.yaci.node.runtime.handlers.YaciTxSubmissionHandler;
import com.bloxbean.cardano.yaci.node.runtime.plugins.PluginManager;
import com.bloxbean.cardano.yaci.node.api.plugin.StorageFilter;
import com.bloxbean.cardano.yaci.node.runtime.utxo.AddressUtxoFilter;
import com.bloxbean.cardano.yaci.node.runtime.utxo.DefaultUtxoStore;
import com.bloxbean.cardano.yaci.node.runtime.utxo.Prunable;
import com.bloxbean.cardano.yaci.node.runtime.utxo.PruneService;
import com.bloxbean.cardano.yaci.node.runtime.utxo.StorageFilterChain;
import com.bloxbean.cardano.yaci.node.runtime.utxo.UtxoEventHandler;
import com.bloxbean.cardano.yaci.node.runtime.utxo.UtxoEventHandlerAsync;
import com.bloxbean.cardano.yaci.node.runtime.utxo.UtxoStatusProvider;
import com.bloxbean.cardano.yaci.node.runtime.utxo.UtxoStoreFactory;
import com.bloxbean.cardano.yaci.node.runtime.utxo.UtxoStoreWriter;
import com.bloxbean.cardano.yaci.node.runtime.validation.DefaultConsensusListener;
import com.bloxbean.cardano.yaci.node.runtime.validation.DefaultTransactionValidatorListener;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
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
    private PeerClient peerClient;  // Primary peer client (backward compat / first upstream)
    private boolean isInitialSyncComplete = false;
    // Pipelining state
    private PipelineConfig pipelineConfig;
    private boolean isPipelinedMode = false;

    // Multi-peer support
    private final List<PeerClient> peerClients = new CopyOnWriteArrayList<>();
    private PeerPool peerPool;
    private HeaderFanIn headerFanIn;
    private BodyFetchScheduler bodyFetchScheduler;
    private ChainSelectionStrategy chainSelectionStrategy;
    private PeerDiscoveryService peerDiscoveryService;

    // Pipeline managers
    private HeaderSyncManager headerSyncManager;
    private BodyFetchManager bodyFetchManager;

    // Remote tip info for sync strategy
    private Tip remoteTip;

    // Server components (for serving other clients)
    private NodeServer nodeServer;
    private final int serverPort;

    // MemPool for transaction handling
    private final MemPool memPool;

    // Block producer (devnet mode)
    private BlockProducer blockProducer;
    private GenesisConfig genesisConfig;
    private long resolvedGenesisTimestamp;
    private SlotTimeCalculator slotTimeCalculator;
    private TransactionValidationService transactionEvaluator;
    private TransactionEvaluationService transactionEvalService;
    private YaciTxSubmissionHandler txSubmissionHandler;
    private MempoolEvictionPolicy mempoolEvictionPolicy;

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
    private UtxoStoreWriter utxoStore;
    private BootstrapDataProvider bootstrapDataProvider;
    private PruneService utxoPruneService;
    private PruneService blockPruneService;
    private UtxoEventHandler utxoEventHandler;
    private UtxoEventHandlerAsync utxoEventHandlerAsync;
    private ScheduledFuture<?> utxoLagTask;

    // App-layer messaging components
    private AppMessageMemPool appMessageMemPool;
    private MessageAuthenticator appMessageAuthenticator;
    private YaciAppMessageHandler appMessageHandler;

    // App-layer consensus & ledger (M2)
    private com.bloxbean.cardano.yaci.node.api.consensus.AppConsensus appConsensus;
    private com.bloxbean.cardano.yaci.node.api.ledger.AppLedger appLedger;
    private final List<com.bloxbean.cardano.yaci.node.runtime.appmsg.AppBlockProducer> appBlockProducers = new ArrayList<>();

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

        // Register default consensus listener (accept-all placeholder)
        var consensusListener = new DefaultConsensusListener();
        AnnotationListenerRegistrar.register(eventBus, consensusListener,
                SubscriptionOptions.builder().build());

        // Initialize plugins (discovery is deferred to start())
        if (this.runtimeOptions.plugins().enabled()) {
            pluginManager = new PluginManager(eventBus, scheduler, this.runtimeOptions.plugins().config(), Thread.currentThread().getContextClassLoader());
        }

        // Phase 3: Initialize UTXO store if enabled and RocksDB is used
        try {
            Object enabledOpt = this.runtimeOptions.globals().get("yaci.node.utxo.enabled");
            boolean utxoEnabled = enabledOpt instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(enabledOpt));
            if (utxoEnabled && (chainState instanceof DirectRocksDBChainState rocks)) {
                this.utxoStore = UtxoStoreFactory.create(rocks, log, this.runtimeOptions.globals());
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
                    this.utxoEventHandlerAsync = new UtxoEventHandlerAsync(eventBus, this.utxoStore);
                    log.info("UTXO store initialized ({}); UtxoEventHandlerAsync registered (applyAsync=true)",
                            (this.utxoStore instanceof UtxoStatusProvider sp) ? sp.storeType() : "?");
                } else {
                    this.utxoEventHandler = new UtxoEventHandler(eventBus, this.utxoStore);
                    log.info("UTXO store initialized ({}); UtxoEventHandler registered",
                            (this.utxoStore instanceof UtxoStatusProvider sp) ? sp.storeType() : "?");
                }
                // Start prune service on virtual-thread scheduler
                long intervalSec = 5L;
                Object po = this.runtimeOptions.globals().get("yaci.node.utxo.prune.schedule.seconds");
                if (po instanceof Number n) intervalSec = Math.max(1L, n.longValue());
                else if (po != null) try { intervalSec = Math.max(1L, Long.parseLong(String.valueOf(po))); } catch (Exception ignored) {}
                this.utxoPruneService = new PruneService((Prunable) this.utxoStore, intervalSec * 1000);
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
                        long lastApplied = (this.utxoStore instanceof UtxoStatusProvider sp)
                                ? sp.getLastAppliedBlock() : 0L;
                        var tip = chainState.getTip();
                        long tipBlock = tip != null ? tip.getBlockNumber() : 0L;
                        long lag = Math.max(0L, tipBlock - lastApplied);

                        if (lag > 0)
                            log.info("metric utxo.lag.blocks={}", lag);

                        if (failIfAbove > 0 && lag > failIfAbove) {
                            log.warn("UTXO lag {} blocks exceeds configured threshold {}", lag, failIfAbove);
                        }
                    } catch (Throwable ignored) {}
                }, lagLogSec, lagLogSec, TimeUnit.SECONDS);
            } else {
                log.info("UTXO store not initialized (enabled={}, rocksdb={})", utxoEnabled, (chainState instanceof DirectRocksDBChainState));
            }
        } catch (Throwable t) {
            log.warn("Failed to initialize UTXO store: {}", t.toString());
        }

        // Phase 4: Initialize block body pruning if configured and RocksDB is used
        try {
            int blockPruneDepth = (int) parseLong(this.runtimeOptions.globals().get("yaci.node.chain.block-body-prune-depth"), 0);
            if (blockPruneDepth > 0 && (chainState instanceof DirectRocksDBChainState rocks)) {
                int pruneBatch = (int) parseLong(this.runtimeOptions.globals().get("yaci.node.chain.block-prune-batch-size"), 200);
                long pruneIntervalSec = parseLong(this.runtimeOptions.globals().get("yaci.node.chain.block-prune-interval-seconds"), 120);
                BlockPruner blockPruner = new BlockPruner(rocks, blockPruneDepth, pruneBatch);
                this.blockPruneService = new PruneService(blockPruner, pruneIntervalSec * 1000);
                this.blockPruneService.start();
                log.info("Block body prune service started (retention={} blocks, batch={}, interval={}s)",
                        blockPruneDepth, pruneBatch, pruneIntervalSec);
            } else {
                log.info("Block body pruning disabled (block-body-prune-depth=0)");
            }
        } catch (Throwable t) {
            log.warn("Failed to initialize block prune service: {}", t.toString());
        }
    }

    @Override
    public UtxoState getUtxoState() {
        return (utxoStore instanceof UtxoState u) ? u : null;
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
     * Start the node (both client and server)
     */
    public void start() {
        if (isRunning.compareAndSet(false, true)) {
            log.info("Starting Yaci Node...");

            // Start server first
            if (config.isEnableServer()) {
                startServer();
            }

            // Always load genesis config if any genesis files are configured (for protocol params, epoch length, etc.)
            if (genesisConfig == null && hasAnyGenesisConfig()) {
                genesisConfig = GenesisConfig.load(
                        config.getShelleyGenesisFile(),
                        config.getByronGenesisFile(),
                        config.getProtocolParametersFile());

                // Propagate epoch length from genesis to config
                if (genesisConfig.getShelleyGenesisData() != null
                        && genesisConfig.getShelleyGenesisData().epochLength() > 0) {
                    config.setEpochLength(genesisConfig.getShelleyGenesisData().epochLength());
                }

                log.info("Genesis config loaded (protocolParams={}, shelleyData={})",
                        genesisConfig.hasProtocolParameters() ? "available" : "none",
                        genesisConfig.getShelleyGenesisData() != null ? "available" : "none");
            }

            // Start block producer if enabled (after server so we can notify)
            if (config.isEnableBlockProducer()) {
                startBlockProducer();
            }

            // Start app block producers if app-layer is enabled with topics
            if (config.isEnableAppLayer() && !appBlockProducers.isEmpty()) {
                startAppBlockProducers();
            }

            // Initialize slot-to-time calculator from genesis data.
            // Done after startBlockProducer() so resolvedGenesisTimestamp is authoritative.
            initSlotTimeCalculator();

            // Initialize mempool eviction policy — evict on block confirmation, TTL, and size cap
            initMempoolEvictionPolicy();

            // Pre-populate genesis UTXOs for relay mode (when not in block-producer mode)
            if (!config.isEnableBlockProducer() && config.getShelleyGenesisFile() != null
                    && chainState.getTip() == null) {
                initializeGenesisUtxos();
            }

            // Bootstrap state if enabled and chain state is empty
            if (config.isEnableBootstrap() && config.isEnableClient()
                    && chainState.getTip() == null && chainState.getHeaderTip() == null) {
                performBootstrap();
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

            // Initialize UTXO filter chain (after plugins so plugin-registered filters are included)
            if (utxoStore != null) {
                initUtxoFilterChain(utxoStore, this.runtimeOptions.globals());
            }

            EventMetadata meta = EventMetadata.builder().origin("node-runtime").build();
            eventBus.publish(new NodeStartedEvent(System.currentTimeMillis()), meta, PublishOptions.builder().build());
        } else {
            log.warn("Node is already running");
        }
    }

    /**
     * Perform bootstrap using the provided data provider.
     * Called automatically during start() if bootstrap is enabled and chain state is empty.
     */
    private void performBootstrap() {
        if (!(chainState instanceof DirectRocksDBChainState rocks)) {
            log.warn("Bootstrap requires RocksDB chain state. Skipping.");
            return;
        }

        if (bootstrapDataProvider == null) {
            throw new IllegalStateException(
                    "Bootstrap is enabled but no BootstrapDataProvider is configured. "
                            + "Set a provider via setBootstrapDataProvider() before calling start().");
        }

        log.info("=== Bootstrap State Mode ===");
        log.info("Block: {}", config.getBootstrapBlockNumber() <= 0 ? "latest" : config.getBootstrapBlockNumber());

        BootstrapService bootstrapService = new BootstrapService(rocks, utxoStore);

        List<BootstrapOutpoint> outpoints = null;
        if (config.getBootstrapUtxos() != null) {
            outpoints = config.getBootstrapUtxos().stream()
                    .map(c -> new BootstrapOutpoint(c.getTxHash(), c.getOutputIndex()))
                    .toList();
        }

        BootstrapResult result = bootstrapService.bootstrap(
                config.getBootstrapBlockNumber(),
                config.getBootstrapAddresses(),
                outpoints,
                bootstrapDataProvider);

        log.info("=== Bootstrap Complete: block #{}, slot={}, {} UTXOs ===",
                result.blockNumber(), result.slot(), result.utxosInjected());
    }

    /**
     * Set the bootstrap data provider. Must be called before start() if bootstrap is enabled.
     */
    public void setBootstrapDataProvider(BootstrapDataProvider provider) {
        this.bootstrapDataProvider = provider;
    }

    /**
     * Get the UTXO store writer. Used by BootstrapResource for incremental UTXO refresh.
     */
    public UtxoStoreWriter getUtxoStoreWriter() {
        return utxoStore;
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
            } else if (config.isEnableBlockProducer()) {
                log.info("Server starting with empty chain state — block producer will create genesis block");
            } else {
                log.error("CRITICAL: Server starting with empty chain state (no tip)");
                log.error("Real Cardano nodes will not connect to an empty server");
                log.error("Yaci Node must sync some blockchain data first before serving");
            }

            // Create TxSubmission handler for transaction processing
            txSubmissionHandler = new YaciTxSubmissionHandler(
                    memPool, eventBus, config.isEnableBlockProducer());

            // Create TxSubmission configuration for periodic requests
            TxSubmissionConfig txSubmissionConfig = TxSubmissionConfig.builder()
                    .batchSize(10)       // 10 transactions per request
                    .useBlockingMode(false)
                    .build();

            // Build agent factories list for additional protocols (e.g., app-layer)
            List<AgentFactory> agentFactories = new ArrayList<>();
            if (config.isEnableAppLayer()) {
                initAppLayerComponents();
                AppMsgSubmissionConfig appMsgConfig = AppMsgSubmissionConfig.builder()
                        .batchSize(10)
                        .useBlockingMode(true)
                        .build();
                agentFactories.add(() -> {
                    var serverAgent = new AppMsgSubmissionServerAgent(appMsgConfig);
                    serverAgent.addListener(appMessageHandler);
                    return serverAgent;
                });
                log.info("App-layer messaging enabled (auth={}, mempool-size={})",
                        config.getAppLayerAuthMode(), config.getAppMessageMemPoolMaxSize());
            }

            // Use app-layer version table when enabled, so connecting Yaci clients can negotiate V100
            var serverVersionTable = config.isEnableAppLayer()
                    ? N2NVersionTableConstant.v11AndAboveWithAppLayer(protocolMagic, false, 0, false)
                    : N2NVersionTableConstant.v11AndAbove(protocolMagic, false, 0, false);

            nodeServer = new NodeServer(serverPort,
                    serverVersionTable,
                    chainState,
                    txSubmissionHandler,
                    txSubmissionConfig,
                    agentFactories);

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
     * Initialize app-layer messaging components (authenticator, mempool, handler)
     * and M2 consensus/ledger/block-production components.
     * Called once when app-layer is enabled, before starting the server.
     */
    private void initAppLayerComponents() {
        if (appMessageMemPool != null) return; // already initialized

        // Create authenticator based on config
        String authMode = config.getAppLayerAuthMode();
        if ("permissioned".equalsIgnoreCase(authMode)) {
            appMessageAuthenticator = new PermissionedAuthenticator(
                    config.getAppLayerAllowedKeys() != null
                            ? new java.util.HashSet<>(config.getAppLayerAllowedKeys())
                            : null);
        } else {
            appMessageAuthenticator = new OpenAuthenticator();
        }

        // Create mempool
        appMessageMemPool = new DefaultAppMessageMemPool(config.getAppMessageMemPoolMaxSize());

        // Create handler
        appMessageHandler = new YaciAppMessageHandler(appMessageMemPool, appMessageAuthenticator, eventBus);

        // Schedule periodic TTL eviction
        scheduler.scheduleAtFixedRate(() -> {
            try {
                appMessageMemPool.removeExpired(System.currentTimeMillis() / 1000);
            } catch (Exception e) {
                log.warn("App message TTL eviction error: {}", e.getMessage());
            }
        }, 30, 30, TimeUnit.SECONDS);

        log.info("App-layer components initialized (auth={}, maxPoolSize={})",
                authMode, config.getAppMessageMemPoolMaxSize());

        // --- M2: Consensus, Ledger, Validation, Block Production ---
        initAppConsensusAndLedger();
    }

    /**
     * Schedule periodic sync of app messages from the local mempool to a client-side
     * AppMsgSubmissionAgent, so the peer's server can pull them via Protocol 100.
     */
    private void scheduleAppMessageSync(AppMsgSubmissionAgent agent, String peerId) {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (appMessageMemPool == null || appMessageMemPool.size() == 0) return;
                List<AppMessage> pending = appMessageMemPool.getMessages(50);
                int enqueued = 0;
                for (AppMessage msg : pending) {
                    if (agent.enqueueMessage(msg)) {
                        enqueued++;
                    }
                }
                if (enqueued > 0) {
                    log.debug("Synced {} app message(s) to agent for peer {}", enqueued, peerId);
                }
            } catch (Exception e) {
                log.warn("App message sync error for peer {}: {}", peerId, e.getMessage());
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    /**
     * Initialize M2 consensus, ledger, validation listeners, and block producers.
     */
    private void initAppConsensusAndLedger() {
        // Create consensus implementation
        String consensusMode = config.getAppConsensusMode();
        if ("multisig".equalsIgnoreCase(consensusMode)) {
            var params = com.bloxbean.cardano.yaci.node.api.consensus.ConsensusParams.builder()
                    .threshold(config.getAppConsensusThreshold())
                    .totalSigners(config.getAppConsensusTotalSigners())
                    .build();
            // Convert allowed keys from hex strings to byte arrays
            List<byte[]> allowedKeys = new ArrayList<>();
            if (config.getAppLayerAllowedKeys() != null) {
                for (String hexKey : config.getAppLayerAllowedKeys()) {
                    allowedKeys.add(HexUtil.decodeHexString(hexKey));
                }
            }
            appConsensus = new com.bloxbean.cardano.yaci.node.runtime.consensus.MultiSigConsensus(allowedKeys, params);
            log.info("App consensus: multisig (threshold={}/{})", params.getThreshold(), params.getTotalSigners());
        } else {
            appConsensus = new com.bloxbean.cardano.yaci.node.runtime.consensus.SingleSignerConsensus();
            log.info("App consensus: single-signer");
        }

        // Create ledger
        if (config.isAppLedgerEnabled()) {
            String ledgerPath = config.getAppLedgerPath();
            if (ledgerPath == null || ledgerPath.isBlank()) {
                // Derive from rocksDB path or use in-memory
                if (config.isUseRocksDB() && config.getRocksDBPath() != null) {
                    ledgerPath = config.getRocksDBPath() + "/app-ledger";
                }
            }
            if (ledgerPath != null && !ledgerPath.isBlank()) {
                appLedger = new com.bloxbean.cardano.yaci.node.runtime.ledger.RocksDBAppLedger(ledgerPath);
                log.info("App ledger: RocksDB (path={})", ledgerPath);
            } else {
                appLedger = new com.bloxbean.cardano.yaci.node.runtime.ledger.InMemoryAppLedger();
                log.info("App ledger: in-memory");
            }
        } else {
            appLedger = new com.bloxbean.cardano.yaci.node.runtime.ledger.InMemoryAppLedger();
            log.info("App ledger: in-memory (ledger persistence disabled)");
        }

        // Register consensus listener
        var consensusListener = new com.bloxbean.cardano.yaci.node.runtime.consensus.DefaultAppConsensusListener(appConsensus);
        AnnotationListenerRegistrar.register(eventBus, consensusListener, SubscriptionOptions.builder().build());

        // Register validation listener (accept-all by default)
        var validator = new com.bloxbean.cardano.yaci.node.runtime.validation.app.DefaultAppDataValidator();
        var validationListener = new com.bloxbean.cardano.yaci.node.runtime.validation.app.DefaultAppValidationListener(validator);
        AnnotationListenerRegistrar.register(eventBus, validationListener, SubscriptionOptions.builder().build());

        // Create block producers for configured topics
        List<String> topics = config.getAppTopics();
        if (topics != null && !topics.isEmpty()) {
            for (String topic : topics) {
                var producer = new com.bloxbean.cardano.yaci.node.runtime.appmsg.AppBlockProducer(
                        appMessageMemPool, appLedger, appConsensus, eventBus,
                        scheduler, topic, config.getAppBlockIntervalMs());
                appBlockProducers.add(producer);
                log.info("App block producer created for topic '{}' (interval={}ms)", topic, config.getAppBlockIntervalMs());
            }
        }

        log.info("App-layer M2 components initialized (consensus={}, ledger={}, topics={})",
                consensusMode, appLedger.getClass().getSimpleName(),
                topics != null ? topics : "[]");
    }

    /**
     * Start all app block producers.
     */
    private void startAppBlockProducers() {
        for (var producer : appBlockProducers) {
            producer.start();
        }
        if (!appBlockProducers.isEmpty()) {
            log.info("Started {} app block producer(s)", appBlockProducers.size());
        }
    }

    /**
     * Stop all app block producers and close the app ledger.
     */
    private void stopAppBlockProducers() {
        for (var producer : appBlockProducers) {
            producer.stop();
        }
        appBlockProducers.clear();
        if (appLedger != null) {
            try {
                appLedger.close();
            } catch (Exception e) {
                log.warn("Error closing app ledger: {}", e.getMessage());
            }
            appLedger = null;
        }
    }

    /**
     * Get the app-message handler. Useful for local message submission.
     */
    public YaciAppMessageHandler getAppMessageHandler() {
        return appMessageHandler;
    }

    /**
     * Get the app-message mempool.
     */
    public AppMessageMemPool getAppMessageMemPool() {
        return appMessageMemPool;
    }

    /**
     * Get the app ledger.
     */
    public com.bloxbean.cardano.yaci.node.api.ledger.AppLedger getAppLedger() {
        return appLedger;
    }

    /**
     * Get the app consensus implementation.
     */
    public com.bloxbean.cardano.yaci.node.api.consensus.AppConsensus getAppConsensus() {
        return appConsensus;
    }

    /**
     * Start the block producer for devnet mode
     */
    private void startBlockProducer() {
        log.info("Starting block producer (devnet mode)...");

        if (genesisConfig == null) {
            genesisConfig = GenesisConfig.load(
                    config.getShelleyGenesisFile(),
                    config.getByronGenesisFile(),
                    config.getProtocolParametersFile());

            // Propagate epoch length from genesis to config so REST layer can use it
            if (genesisConfig.getShelleyGenesisData() != null
                    && genesisConfig.getShelleyGenesisData().epochLength() > 0) {
                config.setEpochLength(genesisConfig.getShelleyGenesisData().epochLength());
            }
        }

        // Auto-derive block time from genesis if blockTimeMillis == 0 (auto sentinel)
        if (config.getBlockTimeMillis() <= 0) {
            if (genesisConfig.getShelleyGenesisData() != null) {
                double activeSlotsCoeff = genesisConfig.getActiveSlotsCoeff();
                if (activeSlotsCoeff <= 0) activeSlotsCoeff = 1.0;
                double slotLength = genesisConfig.getShelleyGenesisData().slotLength();
                int derived = (int) (slotLength * 1000 / activeSlotsCoeff);
                config.setBlockTimeMillis(derived);
                log.info("Auto-derived blockTimeMillis={} from genesis (slotLength={}, activeSlotsCoeff={})",
                        derived, slotLength, activeSlotsCoeff);
            } else {
                config.setBlockTimeMillis(1000);
                log.info("No genesis data available, using default blockTimeMillis=1000");
            }
        } else {
            log.info("Using explicit blockTimeMillis={}", config.getBlockTimeMillis());
        }

        // Auto-derive slotLengthMillis from genesis slotLength
        if (config.getSlotLengthMillis() <= 0) {
            if (genesisConfig.getShelleyGenesisData() != null
                    && genesisConfig.getShelleyGenesisData().slotLength() > 0) {
                int derivedSlotLength = (int) (genesisConfig.getShelleyGenesisData().slotLength() * 1000);
                config.setSlotLengthMillis(derivedSlotLength);
                log.info("Auto-derived slotLengthMillis={} from genesis slotLength={}",
                        derivedSlotLength, genesisConfig.getShelleyGenesisData().slotLength());
            } else {
                config.setSlotLengthMillis(1000);
                log.info("No genesis data available, using default slotLengthMillis=1000");
            }
        } else {
            log.info("Using explicit slotLengthMillis={}", config.getSlotLengthMillis());
        }

        boolean freshStart = chainState.getTip() == null;

        // Resolve genesis timestamp: persist on fresh start, read back on restart
        resolvedGenesisTimestamp = genesisConfig.resolveAndPersistGenesisTimestamp(
                config.getGenesisTimestamp(), freshStart, config.getShelleyGenesisFile());

        blockProducer = new BlockProducer(
                chainState, memPool, nodeServer, eventBus, scheduler,
                config.getBlockTimeMillis(),
                config.isLazyBlockProduction(),
                resolvedGenesisTimestamp,
                config.getSlotLengthMillis(),
                genesisConfig,
                transactionEvaluator,
                getUtxoState());
        blockProducer.start();

        // Store genesis UTXOs directly in UTXO store using blake2b(address) tx hash convention.
        // Only on fresh start (not restart) — genesis block was just produced above.
        if (freshStart && genesisConfig.hasInitialFunds() && utxoStore != null) {
            var tip = chainState.getTip();
            String blockHash = tip != null ? HexUtil.encodeHexString(tip.getBlockHash()) : "";
            long slot = tip != null ? tip.getSlot() : 0;
            utxoStore.storeGenesisUtxos(genesisConfig.getInitialFunds(),
                    config.getProtocolMagic(), slot, 0, blockHash);
        }

        // For devnet block producer mode, era starts at Conway from slot 0
        if (freshStart && chainState instanceof DirectRocksDBChainState rocksState) {
            rocksState.setEraStartSlot(Era.Conway.value, 0);
        }

        log.info("Block producer started");
    }

    /**
     * Initialize the SlotTimeCalculator from genesis config data.
     */
    private void initSlotTimeCalculator() {
        if (genesisConfig == null) return;

        // Use authoritative resolved timestamp from block producer if available
        long networkStartTimeSec = (resolvedGenesisTimestamp > 0)
                ? resolvedGenesisTimestamp / 1000
                : genesisConfig.getNetworkStartTimeSeconds();
        long byronSlotDurationSec = genesisConfig.getByronSlotDurationSeconds();
        double shelleySlotLengthSec = genesisConfig.getShelleySlotLengthSeconds();

        if (networkStartTimeSec > 0) {
            slotTimeCalculator = new SlotTimeCalculator(
                    networkStartTimeSec, byronSlotDurationSec, shelleySlotLengthSec, chainState);
            log.info("SlotTimeCalculator initialized: networkStart={}, byronSlotDuration={}s, shelleySlotLength={}s",
                    networkStartTimeSec, byronSlotDurationSec, shelleySlotLengthSec);

            // Subscribe to BlockAppliedEvent to detect era transitions (only when slot-time is active)
            eventBus.subscribe(BlockAppliedEvent.class, ctx -> {
                BlockAppliedEvent event = ctx.event();
                if (event.era() != null && chainState instanceof DirectRocksDBChainState rocksState) {
                    rocksState.setEraStartSlot(event.era().getValue(), event.slot());
                }
            }, SubscriptionOptions.builder().build());
        }
    }

    /**
     * Initialize the mempool eviction policy. Subscribes to BlockAppliedEvent for
     * block-confirmation eviction, and schedules periodic TTL/size-cap checks.
     */
    private void initMempoolEvictionPolicy() {
        long maxAgeMillis = 30 * 60 * 1000L; // 30 minutes
        int maxSize = 10_000;

        mempoolEvictionPolicy = new DefaultMempoolEvictionPolicy(memPool, maxAgeMillis, maxSize);

        // Subscribe to BlockAppliedEvent for block-confirmation eviction
        eventBus.subscribe(BlockAppliedEvent.class, ctx -> {
            mempoolEvictionPolicy.onBlockApplied(ctx.event());
        }, SubscriptionOptions.builder().build());

        // Schedule periodic TTL/size-cap checks every 30 seconds
        scheduler.scheduleAtFixedRate(() -> {
            try {
                mempoolEvictionPolicy.onPeriodicCheck();
            } catch (Exception e) {
                log.debug("Error in mempool periodic eviction check: {}", e.getMessage());
            }
        }, 30, 30, TimeUnit.SECONDS);

        log.info("Mempool eviction policy initialized (maxAge=30min, maxSize={})", maxSize);
    }

    /**
     * Set the transaction evaluator. Called externally (e.g. from node-app)
     * to inject a concrete evaluator implementation.
     *
     */
    public long getResolvedGenesisTimestamp() {
        return resolvedGenesisTimestamp;
    }

    public void setTransactionEvaluator(TransactionValidator evaluator) {
        if (evaluator == null || getUtxoState() == null) {
            log.info("Transaction evaluation not available: evaluator={}, utxoState={}",
                    evaluator != null ? "provided" : "null",
                    getUtxoState() != null ? "available" : "null");
            return;
        }

        this.transactionEvaluator = new TransactionValidationService(evaluator, getUtxoState());

        // Register default validator listener via event bus (replaces direct wiring)
        boolean defaultValidatorEnabled = resolveBoolean(
                runtimeOptions.globals(), "yaci.node.validation.default-validator-enabled", true);

        if (defaultValidatorEnabled) {
            var validatorListener = new DefaultTransactionValidatorListener(this.transactionEvaluator);
            AnnotationListenerRegistrar.register(eventBus, validatorListener,
                    SubscriptionOptions.builder().build());
            log.info("Default transaction validator listener registered (order=100)");
        } else {
            log.info("Default transaction validator listener DISABLED by config");
        }

        log.info("Transaction evaluator set");
    }

    public void setScriptEvaluator(TransactionEvaluator scriptEvaluator) {
        if (scriptEvaluator == null || getUtxoState() == null) {
            log.info("Script evaluation not available");
            return;
        }
        this.transactionEvalService = new TransactionEvaluationService(scriptEvaluator, getUtxoState());
        log.info("Script evaluator set for /utils/txs/evaluate endpoint");
    }

    public TransactionEvaluationService getTransactionEvalService() {
        return transactionEvalService;
    }

    /**
     * Pre-populate genesis UTXOs for relay mode.
     * Stores an empty genesis block and writes UTXOs directly to the UTXO store
     * using tx_hash = blake2b(address) convention (matching yaci-store and wallets).
     */
    private void initializeGenesisUtxos() {
        log.info("Initializing genesis UTXOs from genesis files...");

        genesisConfig = GenesisConfig.load(
                config.getShelleyGenesisFile(),
                config.getByronGenesisFile(),
                config.getProtocolParametersFile());

        // Propagate epoch length from genesis to config so REST layer can use it
        if (genesisConfig.getShelleyGenesisData() != null
                && genesisConfig.getShelleyGenesisData().epochLength() > 0) {
            config.setEpochLength(genesisConfig.getShelleyGenesisData().epochLength());
        }

        boolean hasFunds = genesisConfig.hasInitialFunds() || genesisConfig.hasByronBalances();

        if (hasFunds) {
            // Store genesis UTXOs directly in UTXO store with blake2b(address) tx hashes.
            String blockHash = "0000000000000000000000000000000000000000000000000000000000000000";

            if (utxoStore != null) {
                if (genesisConfig.hasInitialFunds()) {
                    utxoStore.storeGenesisUtxos(genesisConfig.getInitialFunds(),
                            config.getProtocolMagic(), 0, 0, blockHash);
                }
                if (genesisConfig.hasByronBalances()) {
                    utxoStore.storeByronGenesisUtxos(genesisConfig.getByronBalances(),
                            0, 0, blockHash);
                }
            }

            log.info("Genesis UTXOs stored: {} shelley + {} byron fund entries",
                    genesisConfig.getInitialFunds().size(),
                    genesisConfig.getByronBalances().size());
        } else {
            log.info("No genesis funds found in genesis files");
        }
    }

    private boolean hasAnyGenesisConfig() {
        return (config.getShelleyGenesisFile() != null && !config.getShelleyGenesisFile().isBlank())
                || (config.getByronGenesisFile() != null && !config.getByronGenesisFile().isBlank())
                || (config.getProtocolParametersFile() != null && !config.getProtocolParametersFile().isBlank());
    }

    @Override
    public String submitTransaction(byte[] txCbor) {
        // Compute tx hash upfront for event and return value
        String txHash = TransactionUtil.getTxHash(txCbor);

        // Publish validation event — synchronous listeners will veto if invalid
        var validateEvent = new TransactionValidateEvent(txCbor, txHash, "rest-api");
        eventBus.publish(validateEvent,
                EventMetadata.builder().origin("rest-api").build(),
                PublishOptions.builder().build());

        if (validateEvent.isRejected()) {
            throw new TransactionValidationException(validateEvent.rejections());
        }

        var mpt = memPool.addTransaction(txCbor);
        if (eventBus != null && mpt != null) {
            eventBus.publish(new MemPoolTransactionReceivedEvent(mpt),
                    EventMetadata.builder().origin("rest-api").build(),
                    PublishOptions.builder().build());
        }

        // Forward to upstream nodes if connected (relay mode)
        for (PeerClient pc : peerClients) {
            if (pc != null && pc.isRunning()) {
                try {
                    pc.submitTxBytes(txHash, txCbor, TxBodyType.CONWAY);
                    log.debug("Transaction {} forwarded to upstream peer", txHash);
                } catch (Exception e) {
                    log.warn("Failed to forward transaction {} to upstream peer: {}", txHash, e.getMessage());
                }
            }
        }

        return txHash;
    }

    @Override
    public String getProtocolParameters() {
        return genesisConfig != null ? genesisConfig.getProtocolParameters() : null;
    }

    @Override
    public GenesisParameters getGenesisParameters() {
        if (genesisConfig == null || genesisConfig.getShelleyGenesisData() == null) {
            return null;
        }
        var d = genesisConfig.getShelleyGenesisData();
        return new GenesisParameters(
                d.activeSlotsCoeff(),
                d.updateQuorum(),
                String.valueOf(d.maxLovelaceSupply()),
                d.networkMagic(),
                d.epochLength(),
                d.systemStart(),
                d.slotsPerKESPeriod(),
                (int) d.slotLength(),
                d.maxKESEvolutions(),
                d.securityParam()
        );
    }

    @Override
    public void rollbackTo(long targetSlot) {
        requireDevMode("Rollback");

        ChainTip currentTip = chainState.getTip();
        if (currentTip == null) {
            throw new IllegalStateException("No chain tip available - chain is empty");
        }

        if (targetSlot < 0) {
            throw new IllegalArgumentException("Target slot must be >= 0, got: " + targetSlot);
        }

        if (targetSlot >= currentTip.getSlot()) {
            throw new IllegalArgumentException("Target slot " + targetSlot
                    + " must be less than current tip slot " + currentTip.getSlot());
        }

        log.info("API-triggered rollback: target slot={}, current tip slot={}, block={}",
                targetSlot, currentTip.getSlot(), currentTip.getBlockNumber());

        // 1. Stop block producer
        boolean wasRunning = blockProducer.isRunning();
        if (wasRunning) {
            blockProducer.stop();
        }

        try {
            // 2. Rollback chain state (removes blocks/headers after target slot)
            chainState.rollbackTo(targetSlot);

            // 3. Get new tip after rollback for the Point
            ChainTip newTip = chainState.getTip();
            Point rollbackPoint;
            if (newTip != null) {
                rollbackPoint = new Point(newTip.getSlot(), HexUtil.encodeHexString(newTip.getBlockHash()));
            } else {
                rollbackPoint = new Point(targetSlot, "0000000000000000000000000000000000000000000000000000000000000000");
            }

            // 4. Publish RollbackEvent (isReal=true so UTXO deltas get unwound)
            try {
                EventMetadata meta = EventMetadata.builder().origin("api-rollback").build();
                eventBus.publish(new RollbackEvent(rollbackPoint, true),
                        meta, PublishOptions.builder().build());
            } catch (Exception ex) {
                log.warn("RollbackEvent publish failed: {}", ex.toString());
            }

            // 5. Notify server (ChainSyncServerAgent sends Rollbackward to connected clients)
            if (isServerRunning.get() && nodeServer != null) {
                try {
                    nodeServer.notifyNewDataAvailable();
                    log.info("Notified server agents about API-triggered rollback");
                } catch (Exception e) {
                    log.warn("Error notifying server agents about rollback", e);
                }
            }

            // 6. Reset block producer to resume from new tip
            blockProducer.resetToChainTip();

            // Update last known tip
            lastKnownChainTip = chainState.getTip();

            log.info("API-triggered rollback complete: new tip slot={}, block={}",
                    newTip != null ? newTip.getSlot() : "null",
                    newTip != null ? newTip.getBlockNumber() : "null");
        } finally {
            // 7. Resume block producer
            if (wasRunning) {
                blockProducer.start();
            }
        }
    }

    // --- Devnet developer tools: Snapshot, Fund, Time Advance ---

    private static final int MAX_TIME_ADVANCE_SLOTS = 100_000;

    private void requireDevMode(String operation) {
        if (!config.isDevMode()) {
            throw new IllegalStateException(operation + " requires dev mode (yaci.node.dev-mode=true)");
        }
        if (blockProducer == null) {
            throw new IllegalStateException(operation + " requires block producer to be running");
        }
    }

    @Override
    public SnapshotInfo createSnapshot(String name) {
        requireDevMode("Snapshot");
        if (!(chainState instanceof DirectRocksDBChainState rocksState)) {
            throw new IllegalStateException("Snapshots require RocksDB storage");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Snapshot name must not be empty");
        }

        // Snapshot dir: <dbPath>/../snapshots/<name>/
        Path snapshotsDir = Path.of(rocksState.getDbPath()).getParent().resolve("snapshots");
        Path snapshotDir = snapshotsDir.resolve(name);
        Path checkpointDir = snapshotDir.resolve("checkpoint");

        if (Files.exists(snapshotDir)) {
            throw new IllegalArgumentException("Snapshot '" + name + "' already exists");
        }

        // Pause block producer during snapshot
        boolean wasRunning = blockProducer.isRunning();
        if (wasRunning) blockProducer.stop();

        try {
            Files.createDirectories(snapshotDir);
            rocksState.createSnapshot(checkpointDir.toString());

            ChainTip tip = chainState.getTip();
            long slot = tip != null ? tip.getSlot() : 0;
            long blockNumber = tip != null ? tip.getBlockNumber() : 0;
            long createdAt = System.currentTimeMillis();

            // Write metadata
            var metaJson = String.format(
                    "{\"name\":\"%s\",\"slot\":%d,\"blockNumber\":%d,\"createdAt\":%d}",
                    name, slot, blockNumber, createdAt);
            Files.writeString(snapshotDir.resolve("snapshot-meta.json"), metaJson);

            log.info("Snapshot '{}' created: slot={}, block={}", name, slot, blockNumber);
            return new SnapshotInfo(name, slot, blockNumber, createdAt);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create snapshot '" + name + "'", e);
        } finally {
            if (wasRunning) blockProducer.start();
        }
    }

    @Override
    public void restoreSnapshot(String name) {
        requireDevMode("Restore");
        if (!(chainState instanceof DirectRocksDBChainState rocksState)) {
            throw new IllegalStateException("Snapshots require RocksDB storage");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Snapshot name must not be empty");
        }

        Path snapshotsDir = Path.of(rocksState.getDbPath()).getParent().resolve("snapshots");
        Path snapshotDir = snapshotsDir.resolve(name);
        Path checkpointDir = snapshotDir.resolve("checkpoint");

        if (!Files.isDirectory(checkpointDir)) {
            throw new IllegalArgumentException("Snapshot '" + name + "' does not exist");
        }

        log.info("Restoring snapshot '{}'...", name);

        // 1. Stop block producer
        boolean wasRunning = blockProducer.isRunning();
        if (wasRunning) blockProducer.stop();

        try {
            // 2. Restore DB from snapshot
            rocksState.restoreFromSnapshot(checkpointDir.toString());

            // 2b. Reinitialize UTXO store with new DB handles
            if (utxoStore != null) {
                utxoStore.reinitialize();
            }

            // 3. Clear mempool
            if (memPool != null) {
                memPool.clear();
            }

            // 4. Reset block producer to new chain tip
            blockProducer.resetToChainTip();

            // 5. Notify server — connected clients get informed
            if (isServerRunning.get() && nodeServer != null) {
                try {
                    nodeServer.notifyNewDataAvailable();
                } catch (Exception e) {
                    log.warn("Error notifying server after snapshot restore", e);
                }
            }

            // Invalidate slot-time cache so it re-reads era data from restored DB
            if (slotTimeCalculator != null) {
                slotTimeCalculator.invalidateCache();
            }

            // Update last known tip
            ChainTip newTip = chainState.getTip();
            lastKnownChainTip = newTip;
            log.info("Snapshot '{}' restored: new tip slot={}, block={}",
                    name,
                    newTip != null ? newTip.getSlot() : "null",
                    newTip != null ? newTip.getBlockNumber() : "null");
        } finally {
            // 6. Resume block producer
            if (wasRunning) blockProducer.start();
        }
    }

    @Override
    public List<SnapshotInfo> listSnapshots() {
        if (!(chainState instanceof DirectRocksDBChainState rocksState)) {
            return List.of();
        }

        Path snapshotsDir = Path.of(rocksState.getDbPath()).getParent().resolve("snapshots");
        if (!Files.isDirectory(snapshotsDir)) {
            return List.of();
        }

        var results = new ArrayList<SnapshotInfo>();
        try (var dirs = Files.list(snapshotsDir)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                Path metaFile = dir.resolve("snapshot-meta.json");
                if (Files.exists(metaFile)) {
                    try {
                        String json = Files.readString(metaFile);
                        var info = parseSnapshotMeta(json);
                        if (info != null) results.add(info);
                    } catch (Exception e) {
                        log.warn("Failed to read snapshot metadata: {}", metaFile, e);
                    }
                }
            });
        } catch (Exception e) {
            log.warn("Failed to list snapshots", e);
        }

        results.sort(Comparator.comparingLong(SnapshotInfo::createdAt));
        return results;
    }

    @Override
    public void deleteSnapshot(String name) {
        if (!(chainState instanceof DirectRocksDBChainState rocksState)) {
            throw new IllegalStateException("Snapshots require RocksDB storage");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Snapshot name must not be empty");
        }

        Path snapshotsDir = Path.of(rocksState.getDbPath()).getParent().resolve("snapshots");
        Path snapshotDir = snapshotsDir.resolve(name);

        if (!Files.isDirectory(snapshotDir)) {
            throw new IllegalArgumentException("Snapshot '" + name + "' does not exist");
        }

        try {
            deleteRecursively(snapshotDir);
            log.info("Snapshot '{}' deleted", name);
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete snapshot '" + name + "'", e);
        }
    }

    @Override
    public FundResult fundAddress(String address, long lovelace) {
        requireDevMode("Faucet");
        if (utxoStore == null || !utxoStore.isEnabled()) {
            throw new IllegalStateException("Faucet requires UTXO store to be enabled");
        }
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("Address must not be empty");
        }
        if (lovelace <= 0) {
            throw new IllegalArgumentException("Lovelace amount must be positive");
        }

        String txHash = utxoStore.injectFaucetUtxo(address, lovelace);
        return new FundResult(txHash, 0, lovelace);
    }

    @Override
    public TimeAdvanceResult advanceTimeBySlots(int slots) {
        requireDevMode("Time advance");
        if (slots <= 0) {
            throw new IllegalArgumentException("Slots must be positive, got: " + slots);
        }
        if (slots > MAX_TIME_ADVANCE_SLOTS) {
            throw new IllegalArgumentException("Cannot advance more than " + MAX_TIME_ADVANCE_SLOTS + " slots per request");
        }

        ChainTip currentTip = chainState.getTip();
        long currentSlot = currentTip != null ? currentTip.getSlot() : 0;
        long targetSlot = currentSlot + slots;

        // Stop scheduled block producer, produce rapid blocks, resume
        boolean wasRunning = blockProducer.isRunning();
        if (wasRunning) blockProducer.stop();

        try {
            int blocksProduced = blockProducer.produceEmptyBlocksToSlot(targetSlot);

            ChainTip newTip = chainState.getTip();
            lastKnownChainTip = newTip;

            return new TimeAdvanceResult(
                    newTip != null ? newTip.getSlot() : 0,
                    newTip != null ? newTip.getBlockNumber() : 0,
                    blocksProduced);
        } finally {
            if (wasRunning) blockProducer.start();
        }
    }

    @Override
    public TimeAdvanceResult advanceTimeBySeconds(int seconds) {
        requireDevMode("Time advance");
        if (seconds <= 0) {
            throw new IllegalArgumentException("Seconds must be positive, got: " + seconds);
        }

        int slotLengthMs = blockProducer.getSlotLengthMillis();
        if (slotLengthMs <= 0) {
            throw new IllegalStateException("Slot length is not configured");
        }

        int slots = (int) ((long) seconds * 1000 / slotLengthMs);
        if (slots <= 0) slots = 1;

        return advanceTimeBySlots(slots);
    }

    @Override
    public long slotToUnixTime(long slot) {
        if (slotTimeCalculator != null) {
            return slotTimeCalculator.slotToUnixTime(slot);
        }
        return 0;
    }

    private static SnapshotInfo parseSnapshotMeta(String json) {
        // Minimal JSON parsing without adding a dependency
        try {
            String name = extractJsonString(json, "name");
            long slot = extractJsonLong(json, "slot");
            long blockNumber = extractJsonLong(json, "blockNumber");
            long createdAt = extractJsonLong(json, "createdAt");
            return new SnapshotInfo(name, slot, blockNumber, createdAt);
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) return null;
        start += search.length();
        int end = json.indexOf("\"", start);
        return json.substring(start, end);
    }

    private static long extractJsonLong(String json, String key) {
        String search = "\"" + key + "\":";
        int start = json.indexOf(search);
        if (start < 0) return 0;
        start += search.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        return Long.parseLong(json.substring(start, end));
    }

    private static void deleteRecursively(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Start the client sync component using either pipelined or sequential sync.
     * Supports multiple upstream peers when configured via {@code upstreams} list.
     */
    private void startClientSync() {
        try {
            boolean usePipeline = config.isEnablePipelinedSync();
            isSyncing.set(true);
            isPipelinedMode = usePipeline;

            // Resolve effective upstream list (upstreams config or single remote host/port)
            List<UpstreamConfig> upstreams = config.getEffectiveUpstreams();
            boolean multiPeer = upstreams.size() > 1;

            log.info("Starting {} client sync with {} upstream peer(s)...",
                    usePipeline ? "pipelined" : "sequential", upstreams.size());
            for (UpstreamConfig u : upstreams) {
                log.info("  Upstream: {}:{} (type={})", u.getHost(), u.getPort(), u.getType());
            }

            // Get local tips to determine sync strategy
            ChainTip headerTip = chainState.getHeaderTip();
            ChainTip bodyTip = chainState.getTip();
            ChainTip localTip = headerTip != null ? headerTip : bodyTip;

            log.info("Local header_tip: {}, body_tip: {}, using: {} for sync",
                     headerTip, bodyTip, localTip != null ? "slot " + localTip.getSlot() : "genesis");

            lastKnownChainTip = localTip;
            Point startPoint = determineStartPoint(localTip);
            log.info("Starting sync from point: {}", startPoint);

            // Initialize chain selection strategy (k from genesis securityParam)
            long securityParam = 2160; // default for mainnet/preprod
            if (genesisConfig != null && genesisConfig.getShelleyGenesisData() != null) {
                long k = genesisConfig.getShelleyGenesisData().securityParam();
                if (k > 0) securityParam = k;
            }
            chainSelectionStrategy = new PraosChainSelection(securityParam);
            log.info("Chain selection strategy: PraosChainSelection (k={})", securityParam);

            // Initialize PeerPool
            peerPool = new PeerPool(chainSelectionStrategy);
            headerFanIn = new HeaderFanIn(peerPool, chainState, eventBus, chainSelectionStrategy);
            bodyFetchScheduler = new BodyFetchScheduler(peerPool);

            // Find remote tip from primary upstream (non-fatal: YACI peers may have no chain data)
            UpstreamConfig primary = upstreams.get(0);
            try {
                TipFinder tipFinder = new TipFinder(primary.getHost(), primary.getPort(), startPoint, protocolMagic);
                remoteTip = tipFinder.find()
                        .doFinally(signalType -> tipFinder.shutdown())
                        .block(Duration.ofSeconds(5));
            } catch (Exception e) {
                log.warn("TipFinder failed for primary upstream {}:{} - falling back to sequential sync: {}",
                        primary.getHost(), primary.getPort(), e.getMessage());
                remoteTip = null;
                isPipelinedMode = false;
            }

            // Initialize app-layer components early if enabled (needed before creating PeerClients)
            if (config.isEnableAppLayer()) {
                initAppLayerComponents();
            }

            // Create PeerClient for each upstream and add to pool
            for (UpstreamConfig upstream : upstreams) {
                // Use app-layer version table when enabled; L1 peers will ignore V100
                // and negotiate V14, so this is safe for all peer types.
                var clientVersionTable = config.isEnableAppLayer()
                        ? N2NVersionTableConstant.v11AndAboveWithAppLayer(protocolMagic)
                        : N2NVersionTableConstant.v11AndAbove(protocolMagic);
                PeerClient pc = new PeerClient(upstream.getHost(), upstream.getPort(), startPoint, clientVersionTable);

                // Signal intent to use app-layer protocol; version negotiation
                // in AppProtocolManager.onHandshakeComplete() handles the rest.
                if (config.isEnableAppLayer()) {
                    pc.getAppProtocolManager().enableAppMsg();
                }

                peerClients.add(pc);
                peerPool.addPeer(upstream);
            }

            // Primary peerClient = first one (backward compat for HeaderSyncManager/BodyFetchManager)
            peerClient = peerClients.get(0);

            if (isPipelinedMode && remoteTip != null) {
                startPipelinedClientSync(localTip, remoteTip, startPoint);
            } else {
                startSequentialClientSync(startPoint);
            }

            // Connect additional peers (index 1+) for multi-peer mode
            if (multiPeer) {
                connectAdditionalPeers(startPoint);
            }

            // Start peer discovery service if enabled
            if (config.isPeerDiscoveryEnabled() && headerFanIn != null) {
                peerDiscoveryService = new PeerDiscoveryService(
                        peerPool, headerFanIn, eventBus, peerClients,
                        protocolMagic,
                        config.getPeerDiscoveryMaxPeers(),
                        config.getPeerDiscoveryIntervalSeconds());
                peerDiscoveryService.start(startPoint);
            }

        } catch (Exception e) {
            log.error("Failed to start client sync", e);
            isSyncing.set(false);
            isPipelinedMode = false;
            throw new RuntimeException("Failed to start client sync", e);
        }
    }

    /**
     * Connect additional upstream peers (beyond the primary) for multi-peer sync.
     * Each additional peer runs its own ChainSync and feeds headers into HeaderFanIn.
     */
    private void connectAdditionalPeers(Point startPoint) {
        List<UpstreamConfig> upstreams = config.getEffectiveUpstreams();
        for (int i = 1; i < upstreams.size(); i++) {
            UpstreamConfig upstream = upstreams.get(i);
            PeerClient pc = peerClients.get(i);
            String peerId = upstream.peerId();

            try {
                // Create a listener that feeds headers into HeaderFanIn for this peer
                BlockChainDataListener fanInListener = new MultiPeerHeaderListener(
                        peerId, headerFanIn, peerPool, eventBus);

                pc.connect(fanInListener, null);
                pc.enableTxSubmission();
                pc.startHeaderSync(startPoint, true);

                // Schedule app message sync via PeerClient's internal agent
                if (config.isEnableAppLayer()) {
                    pc.getAppProtocolManager().getAppMsgSubmissionAgent()
                            .ifPresent(agent -> scheduleAppMessageSync(agent, peerId));
                }

                // Mark connected in pool
                peerPool.getPeer(peerId).ifPresent(conn -> {
                    conn.markConnected();
                    EventMetadata meta = EventMetadata.builder().origin("node-runtime").build();
                    eventBus.publish(new PeerConnectedEvent(peerId, upstream.getHost(),
                                    upstream.getPort(), upstream.getType()),
                            meta, PublishOptions.builder().build());
                });

                log.info("Additional peer connected: {} ({})", peerId, upstream.getType());
            } catch (Exception e) {
                log.warn("Failed to connect additional peer {}: {}", peerId, e.getMessage());
                peerPool.getPeer(peerId).ifPresent(PeerConnection::recordFailure);
            }
        }
    }

    /**
     * Start pipelined client sync with parallel ChainSync and BlockFetch
     */
    private void startPipelinedClientSync(ChainTip localTip, Tip remoteTip, Point startPoint) {
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

        // Wire HeaderFanIn for multi-peer chain selection (primary peer)
        if (headerFanIn != null && !config.getEffectiveUpstreams().isEmpty()) {
            String primaryPeerId = config.getEffectiveUpstreams().get(0).peerId();
            pipelineListener.setHeaderFanIn(headerFanIn, primaryPeerId);

            // Mark primary peer as connected in pool
            peerPool.getPeer(primaryPeerId).ifPresent(conn -> {
                conn.markConnected();
                EventMetadata meta = EventMetadata.builder().origin("node-runtime").build();
                var primaryUpstream = config.getEffectiveUpstreams().get(0);
                eventBus.publish(new PeerConnectedEvent(primaryPeerId, primaryUpstream.getHost(),
                                primaryUpstream.getPort(), primaryUpstream.getType()),
                        meta, PublishOptions.builder().build());
            });
        }

        // Connect using existing PeerClient.connect() method with pipeline listener
        peerClient.connect(pipelineListener, null); // TxSubmission handled separately if needed
        peerClient.enableTxSubmission(); // Initiate TxSubmission N2N protocol (sends Init message)

        // Schedule app message sync via PeerClient's internal agent
        if (config.isEnableAppLayer()) {
            String primaryPeerId2 = config.getEffectiveUpstreams().get(0).peerId();
            peerClient.getAppProtocolManager().getAppMsgSubmissionAgent()
                    .ifPresent(agent -> scheduleAppMessageSync(agent, primaryPeerId2));
        }

        // Start header-only sync
        peerClient.startHeaderSync(startPoint, true); // Enable pipelining for headers
        log.info("Header sync started with pipelining enabled");

        // Start body fetch manager monitoring
        bodyFetchManager.start();
        log.info("Body fetch manager started for range-based fetching");

        log.info("Pipeline startup complete - HeaderSync and BodyFetch active (peers: {})",
                peerClients.size());
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
        peerClient.enableTxSubmission(); // Initiate TxSubmission N2N protocol (sends Init message)

        // Schedule app message sync via PeerClient's internal agent
        if (config.isEnableAppLayer()) {
            String primaryPeerId = config.getEffectiveUpstreams().get(0).peerId();
            peerClient.getAppProtocolManager().getAppMsgSubmissionAgent()
                    .ifPresent(agent -> scheduleAppMessageSync(agent, primaryPeerId));
        }

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
                    eventBus.publish(new SyncStatusChangedEvent(prev, syncPhase), meta, PublishOptions.builder().build());
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

            // Stop client sync — stop all peer clients
            if (isSyncing.get()) {
                for (PeerClient pc : peerClients) {
                    try {
                        if (pc != null && pc.isRunning()) {
                            pc.stop();
                        }
                    } catch (Exception e) {
                        log.warn("Error stopping peerClient", e);
                    }
                }
                peerClients.clear();
                if (peerDiscoveryService != null) {
                    try { peerDiscoveryService.close(); } catch (Exception ignored) {}
                }
                if (peerPool != null) {
                    peerPool.shutdown();
                }
                isSyncing.set(false);
            }

            // Stop block producer
            if (blockProducer != null && blockProducer.isRunning()) {
                blockProducer.stop();
            }

            // Stop app block producers and close app ledger
            stopAppBlockProducers();

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
            try { if (blockPruneService != null) blockPruneService.close(); } catch (Exception ignored) {}
            try { if (utxoLagTask != null) utxoLagTask.cancel(true); } catch (Exception ignored) {}

            // Stop plugins and close event bus
            try { if (pluginManager != null) pluginManager.close(); } catch (Exception ignored) {}
            try { if (utxoEventHandler != null) utxoEventHandler.close(); } catch (Exception ignored) {}
            try { if (utxoEventHandlerAsync != null) utxoEventHandlerAsync.close(); } catch (Exception ignored) {}
            try { eventBus.close(); } catch (Exception ignored) {}

            log.info("Yaci Node stopped");
        }
    }

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
            eventBus.publish(new RollbackEvent(point, isReal), meta, PublishOptions.builder().build());
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
                eventBus.publish(new SyncStatusChangedEvent(prev, syncPhase), meta, PublishOptions.builder().build());
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

    /**
     * Get the list of all peer clients (multi-peer mode).
     */
    public List<PeerClient> getPeerClients() {
        return List.copyOf(peerClients);
    }

    /**
     * Get the PeerPool (multi-peer mode). May be null if not yet started.
     */
    public PeerPool getPeerPool() {
        return peerPool;
    }

    /**
     * Get the HeaderFanIn (multi-peer mode). May be null if not yet started.
     */
    public HeaderFanIn getHeaderFanIn() {
        return headerFanIn;
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

        // Block producer status
        if (config.isEnableBlockProducer()) {
            log.info("BLOCK PRODUCER: ENABLED (devnet)");
            log.info("   Block interval: {}ms", config.getBlockTimeMillis());
            log.info("   Lazy mode: {}", config.isLazyBlockProduction());
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
    public void maybeFastTransitionToSteadyState(Tip remoteTip) {
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

    @SuppressWarnings("unchecked")
    private void initUtxoFilterChain(UtxoStoreWriter store, java.util.Map<String, Object> globals) {
        if (!(store instanceof DefaultUtxoStore defaultStore)) return;
        boolean filterEnabled = resolveBoolean(globals, "yaci.node.filters.utxo.enabled", false);
        if (!filterEnabled) {
            log.info("UTXO storage filtering disabled");
            return;
        }

        java.util.Set<String> addresses = new java.util.HashSet<>();
        java.util.Set<String> paymentCreds = new java.util.HashSet<>();

        Object addrObj = globals.get("yaci.node.filters.utxo.addresses");
        if (addrObj instanceof java.util.Collection<?> c) {
            for (Object a : c) if (a != null) addresses.add(String.valueOf(a));
        } else if (addrObj instanceof String s && !s.isBlank()) {
            addresses.add(s);
        }

        Object pcObj = globals.get("yaci.node.filters.utxo.payment-credentials");
        if (pcObj instanceof java.util.Collection<?> c) {
            for (Object p : c) if (p != null) paymentCreds.add(String.valueOf(p));
        } else if (pcObj instanceof String s && !s.isBlank()) {
            paymentCreds.add(s);
        }

        List<StorageFilter> filters = new ArrayList<>();
        if (!addresses.isEmpty() || !paymentCreds.isEmpty()) {
            filters.add(new AddressUtxoFilter(addresses, paymentCreds));
            log.info("UTXO filter configured: {} addresses, {} payment-credentials", addresses.size(), paymentCreds.size());
        }

        // Also include any plugin-registered filters
        if (pluginManager != null) {
            filters.addAll(pluginManager.getStorageFilters());
        }

        if (!filters.isEmpty()) {
            defaultStore.setFilterChain(new StorageFilterChain(filters));
            log.info("UTXO storage filter chain active with {} filter(s)", filters.size());
        }
    }

    private static long parseLong(Object obj, long def) {
        if (obj instanceof Number n) return n.longValue();
        if (obj != null) {
            try { return Long.parseLong(String.valueOf(obj)); } catch (Exception ignored) {}
        }
        return def;
    }

    private static boolean resolveBoolean(Map<String, Object> globals, String key, boolean def) {
        Object val = globals != null ? globals.get(key) : null;
        if (val instanceof Boolean b) return b;
        if (val != null) {
            try { return Boolean.parseBoolean(String.valueOf(val)); } catch (Exception ignored) {}
        }
        return def;
    }
}
