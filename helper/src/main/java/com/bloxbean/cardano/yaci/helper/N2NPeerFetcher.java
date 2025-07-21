package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.core.common.TxBodyType;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.byron.ByronBlockHead;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbBlock;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbHead;
import com.bloxbean.cardano.yaci.core.model.byron.ByronMainBlock;
import com.bloxbean.cardano.yaci.core.network.TCPNodeClient;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgent;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.n2n.ChainSyncAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.n2n.ChainsyncAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import com.bloxbean.cardano.yaci.core.protocol.keepalive.KeepAliveAgent;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.TxSubmissionAgent;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.TxSubmissionListener;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges.RequestTxIds;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges.RequestTxs;
import com.bloxbean.cardano.yaci.helper.api.Fetcher;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static com.bloxbean.cardano.yaci.core.common.TxBodyType.CONWAY;

/**
 * Enhanced fetcher with pipelining support for high-performance blockchain synchronization.
 * Supports both simple applications (backward compatible) and node implementations (with pipelining).
 *
 * Usage:
 * - Simple applications: Use start() method for automatic header+body sync
 * - Node implementations: Use startChainSyncOnly() + fetchBlockBodies() for independent control
 * - High performance: Use startPipelinedSync() for full pipelining
 */
@Slf4j
public class N2NPeerFetcher implements Fetcher<Block> {

    // Core connection fields
    private final String host;
    private final int port;
    private final VersionTable versionTable;
    private final Point wellKnownPoint;

    // Agents
    private HandshakeAgent handshakeAgent;
    private KeepAliveAgent keepAliveAgent;
    private ChainsyncAgent chainSyncAgent;
    private BlockfetchAgent blockFetchAgent;
    private TxSubmissionAgent txSubmissionAgent;
    private TCPNodeClient n2nClient;

    // Keep alive tracking
    private int lastKeepAliveResponseCookie = 0;
    private long lastKeepAliveResponseTime = 0;

    // Pipelining support
    private PipelineStrategy currentStrategy = PipelineStrategy.SEQUENTIAL;
    private PipelineConfig pipelineConfig = PipelineConfig.defaultClientConfig();
    private PipelineMetrics pipelineMetrics = new PipelineMetrics();

    // Pipelining state
    private final Queue<BlockHeader> pendingHeaders = new ConcurrentLinkedQueue<>();
    private final Queue<ByronBlockHead> pendingByronHeaders = new ConcurrentLinkedQueue<>();
    private final Queue<ByronEbHead> pendingByronEbHeaders = new ConcurrentLinkedQueue<>();
    private final Map<String, Long> requestTimestamps = new ConcurrentHashMap<>();
    private final AtomicBoolean pipelineActive = new AtomicBoolean(false);

    // Threading for parallel processing
    private ExecutorService pipelineExecutor;
    private ScheduledExecutorService batchScheduler;

    // Filters and selectors
    private Predicate<BlockHeader> bodyFetchFilter;
    private final List<Consumer<Block>> blockConsumers = new ArrayList<>();

    // Legacy support - original behavior
    private boolean legacyMode = true;

    private volatile boolean firstTimeHandshake = true;

    /**
     * Construct {@link N2NPeerFetcher} to sync the blockchain
     */
    public N2NPeerFetcher(String host, int port, Point wellKnownPoint, long protocolMagic) {
        this(host, port, wellKnownPoint, N2NVersionTableConstant.v11AndAbove(protocolMagic));
    }

    /**
     * Main constructor - fetcher syncs from the given wellKnownPoint
     * Application controls sync strategy by choosing the starting point
     */
    public N2NPeerFetcher(String host, int port, Point wellKnownPoint, VersionTable versionTable) {
        this.host = host;
        this.port = port;
        this.versionTable = versionTable;
        this.wellKnownPoint = wellKnownPoint;

        init();
    }

    private void init() {
        handshakeAgent = new HandshakeAgent(versionTable);
        keepAliveAgent = new KeepAliveAgent();
        chainSyncAgent = new ChainsyncAgent(new Point[]{wellKnownPoint});
        blockFetchAgent = new BlockfetchAgent();
        txSubmissionAgent = new TxSubmissionAgent();

        blockFetchAgent.resetPoints(wellKnownPoint, wellKnownPoint);
        setupAgentListeners();

        n2nClient = new TCPNodeClient(host, port, handshakeAgent, keepAliveAgent,
                chainSyncAgent, blockFetchAgent, txSubmissionAgent);
    }

    private void setupAgentListeners() {
        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                keepAliveAgent.sendKeepAlive(1234);

                // Start appropriate sync based on current strategy
//                if (currentStrategy == PipelineStrategy.HEADERS_ONLY) {
////                    chainSyncAgent.sendNextMessage();
//                } else if (legacyMode) {
////                    chainSyncAgent.sendNextMessage();
//                } else {
////                    startPipelineProcessing();
//                }

                //We don't need to start chain sync here, as it will be started by the application
                //by invoking startXXX() methods.
                if (firstTimeHandshake) {
                    firstTimeHandshake = false;
                    log.info("First time handshake completed. No need to send next message >>>>>>>");
                } else {
                    log.info("Not first time handshake. Sending next message >>>>>>");
                    chainSyncAgent.sendNextMessage();
                }

            }
        });

        chainSyncAgent.addListener(new ChainSyncAgentListener() {
            @Override
            public void intersactFound(Tip tip, Point point) {
                handleIntersectFound(tip, point);
            }

            @Override
            public void intersactNotFound(Tip tip) {
                log.error("IntersactNotFound: {}", tip);
                pipelineMetrics.recordError(PipelineMetrics.ErrorType.HEADER_ERROR);
            }

            @Override
            public void rollforward(Tip tip, BlockHeader blockHeader) {
                handleRollForward(tip, blockHeader);
            }

            @Override
            public void rollforwardByronEra(Tip tip, ByronBlockHead byronHead) {
                handleByronRollForward(tip, byronHead);
            }

            @Override
            public void rollforwardByronEra(Tip tip, ByronEbHead byronEbHead) {
                handleByronEbRollForward(tip, byronEbHead);
            }

            @Override
            public void rollbackward(Tip tip, Point toPoint) {
                handleRollbackward(tip, toPoint);
            }
        });

        blockFetchAgent.addListener(new BlockfetchAgentListener() {
            @Override
            public void blockFound(Block block) {
                handleBlockFound(block);
            }

            @Override
            public void byronBlockFound(ByronMainBlock byronBlock) {
                handleByronBlockFound(byronBlock);
            }

            @Override
            public void byronEbBlockFound(ByronEbBlock byronEbBlock) {
                handleByronEbBlockFound(byronEbBlock);
            }
        });

        keepAliveAgent.addListener(response -> {
            lastKeepAliveResponseCookie = response.getCookie();
            lastKeepAliveResponseTime = System.currentTimeMillis();
        });

        txSubmissionAgent.addListener(new TxSubmissionListener() {
            @Override
            public void handleRequestTxs(RequestTxs requestTxs) {
                txSubmissionAgent.sendNextMessage();
            }

            @Override
            public void handleRequestTxIdsNonBlocking(RequestTxIds requestTxIds) {
                txSubmissionAgent.sendNextMessage();
            }

            @Override
            public void handleRequestTxIdsBlocking(RequestTxIds requestTxIds) {
                if (txSubmissionAgent.hasPendingTx()) {
                    txSubmissionAgent.sendNextMessage();
                }
            }
        });
    }

    // ========================================
    // INTERSECTION AND ROLLFORWARD HANDLING
    // ========================================

    private void handleIntersectFound(Tip tip, Point point) {
        if (log.isDebugEnabled()) {
            log.debug("Intersect found : Point : {},  Tip: {}", point, tip);
        }

        // Simple approach: intersection found, continue from this point
        // No need to reset - let ChainsyncAgent handle it naturally
        chainSyncAgent.sendNextMessage();
    }

    private void handleRollForward(Tip tip, BlockHeader blockHeader) {
        pipelineMetrics.recordHeaderReceived();

        if (legacyMode) {
            // Original behavior - immediately fetch body
            resetBlockFetchAgentAndFetchBlock(blockHeader.getHeaderBody().getSlot(),
                                            blockHeader.getHeaderBody().getBlockHash());
        } else {
            // Pipeline mode - queue header and continue
            processHeaderInPipeline(blockHeader);
        }
    }

    private void handleByronRollForward(Tip tip, ByronBlockHead byronHead) {
        pipelineMetrics.recordHeaderReceived();

        if (legacyMode) {
            resetBlockFetchAgentAndFetchBlock(byronHead.getConsensusData().getAbsoluteSlot(),
                                            byronHead.getBlockHash());
        } else {
            processByronHeaderInPipeline(byronHead);
        }
    }

    private void handleByronEbRollForward(Tip tip, ByronEbHead byronEbHead) {
        pipelineMetrics.recordHeaderReceived();

        if (legacyMode) {
            resetBlockFetchAgentAndFetchBlock(byronEbHead.getConsensusData().getAbsoluteSlot(),
                                            byronEbHead.getBlockHash());
        } else {
            processByronEbHeaderInPipeline(byronEbHead);
        }
    }

    private void handleRollbackward(Tip tip, Point toPoint) {
        if (toPoint.getSlot() == 0) {
            System.out.println("Rollback to genesis point...");
        }

        log.info("Rollback to point: {}", toPoint);

        // Clear pipeline state on rollback
        clearPipelineState();

        chainSyncAgent.sendNextMessage();
    }

    // ========================================
    // BLOCK FOUND HANDLING
    // ========================================

    private void handleBlockFound(Block block) {
        pipelineMetrics.recordBodyReceived(block.getCbor() != null ? block.getCbor().length() : 0);

        if (log.isDebugEnabled()) {
            log.debug("Block Found >> " + block);
        }

        // Notify consumers
        notifyBlockConsumers(block);

        if (legacyMode) {
            // Original behavior - immediately request next header
            chainSyncAgent.sendNextMessage();
        } else {
            // Pipeline mode - process in background
            processBatchCompleted();
        }
    }

    private void handleByronBlockFound(ByronMainBlock byronBlock) {
        pipelineMetrics.recordBodyReceived(byronBlock.getCbor() != null ? byronBlock.getCbor().length() : 0);

        if (legacyMode) {
            chainSyncAgent.sendNextMessage();
        } else {
            processBatchCompleted();
        }
    }

    private void handleByronEbBlockFound(ByronEbBlock byronEbBlock) {
        pipelineMetrics.recordBodyReceived(byronEbBlock.getCbor() != null ? byronEbBlock.getCbor().length() : 0);

        if (legacyMode) {
            chainSyncAgent.sendNextMessage();
        } else {
            processBatchCompleted();
        }
    }

    // ========================================
    // PIPELINE PROCESSING
    // ========================================

    private void processHeaderInPipeline(BlockHeader blockHeader) {
        if (currentStrategy == PipelineStrategy.HEADERS_ONLY) {
            // Headers only - skip body fetch
            pipelineMetrics.recordHeaderProcessed();
            requestNextHeaderIfNeeded();
            return;
        }

        if (bodyFetchFilter != null && !bodyFetchFilter.test(blockHeader)) {
            // Skip body fetch for this header
            pipelineMetrics.recordHeaderProcessed();
            requestNextHeaderIfNeeded();
            return;
        }

        // Queue for body fetch
        pendingHeaders.offer(blockHeader);
        pipelineMetrics.recordHeaderQueuedForBodyFetch();

        requestNextHeaderIfNeeded();
        processPendingBodiesIfNeeded();
    }

    private void processByronHeaderInPipeline(ByronBlockHead byronHead) {
        if (currentStrategy == PipelineStrategy.HEADERS_ONLY) {
            pipelineMetrics.recordHeaderProcessed();
            requestNextHeaderIfNeeded();
            return;
        }

        pendingByronHeaders.offer(byronHead);
        pipelineMetrics.recordHeaderQueuedForBodyFetch();

        requestNextHeaderIfNeeded();
        processPendingBodiesIfNeeded();
    }

    private void processByronEbHeaderInPipeline(ByronEbHead byronEbHead) {
        if (currentStrategy == PipelineStrategy.HEADERS_ONLY) {
            pipelineMetrics.recordHeaderProcessed();
            requestNextHeaderIfNeeded();
            return;
        }

        pendingByronEbHeaders.offer(byronEbHead);
        pipelineMetrics.recordHeaderQueuedForBodyFetch();

        requestNextHeaderIfNeeded();
        processPendingBodiesIfNeeded();
    }

    private void requestNextHeaderIfNeeded() {
        int totalPending = pipelineMetrics.getHeadersInPipeline().get() +
                          pipelineMetrics.getHeadersPendingBodyFetch().get();

        if (totalPending < pipelineConfig.getHeaderPipelineDepth()) {
            chainSyncAgent.sendNextMessage();
        }
    }

    private void processPendingBodiesIfNeeded() {
        if (!pipelineActive.get()) return;

        int totalPending = pendingHeaders.size() + pendingByronHeaders.size() + pendingByronEbHeaders.size();

        if (totalPending >= pipelineConfig.getBodyBatchSize() ||
            (totalPending > 0 && shouldForceBatch())) {

            if (pipelineConfig.isEnableParallelProcessing() && pipelineExecutor != null) {
                pipelineExecutor.submit(this::processBatchOfBodies);
            } else {
                processBatchOfBodies();
            }
        }
    }

    private boolean shouldForceBatch() {
        // Force batch if we've been waiting too long
        return System.currentTimeMillis() - pipelineMetrics.getLastActivity().toEpochMilli() >
               pipelineConfig.getBatchTimeout().toMillis();
    }

    private void processBatchOfBodies() {
        List<Point> batchPoints = new ArrayList<>();

        // Collect batch from pending headers
        for (int i = 0; i < pipelineConfig.getBodyBatchSize() && !pendingHeaders.isEmpty(); i++) {
            BlockHeader header = pendingHeaders.poll();
            if (header != null) {
                Point point = new Point(header.getHeaderBody().getSlot(),
                                      header.getHeaderBody().getBlockHash());
                batchPoints.add(point);
                requestTimestamps.put(point.getHash(), System.currentTimeMillis());
            }
        }

        // Collect Byron headers
        for (int i = 0; i < pipelineConfig.getBodyBatchSize() && !pendingByronHeaders.isEmpty() && batchPoints.size() < pipelineConfig.getBodyBatchSize(); i++) {
            ByronBlockHead header = pendingByronHeaders.poll();
            if (header != null) {
                Point point = new Point(header.getConsensusData().getAbsoluteSlot(),
                                      header.getBlockHash());
                batchPoints.add(point);
                requestTimestamps.put(point.getHash(), System.currentTimeMillis());
            }
        }

        // Collect Byron EB headers
        for (int i = 0; i < pipelineConfig.getBodyBatchSize() && !pendingByronEbHeaders.isEmpty() && batchPoints.size() < pipelineConfig.getBodyBatchSize(); i++) {
            ByronEbHead header = pendingByronEbHeaders.poll();
            if (header != null) {
                Point point = new Point(header.getConsensusData().getAbsoluteSlot(),
                                      header.getBlockHash());
                batchPoints.add(point);
                requestTimestamps.put(point.getHash(), System.currentTimeMillis());
            }
        }

        if (!batchPoints.isEmpty()) {
            fetchBatchBodies(batchPoints);
        }
    }

    private void fetchBatchBodies(List<Point> batchPoints) {
        if (batchPoints.isEmpty()) return;

        pipelineMetrics.recordBodyBatchRequested(batchPoints.size());

        // For now, fetch one at a time (BlockfetchAgent doesn't support batch natively)
        // TODO: Enhance BlockfetchAgent to support true batch requests
        for (Point point : batchPoints) {
            blockFetchAgent.resetPoints(point, point);
            blockFetchAgent.sendNextMessage();
        }
    }

    private void processBatchCompleted() {
        pipelineMetrics.recordBodyBatchCompleted();

        // Continue processing pipeline
        if (pipelineActive.get()) {
            processPendingBodiesIfNeeded();
            requestNextHeaderIfNeeded();
        }
    }

    private void startPipelineProcessing() {
        if (!pipelineActive.compareAndSet(false, true)) {
            return; // Already active
        }

        log.info("Starting pipeline processing with strategy: {}", currentStrategy);

        // Initialize thread pools if parallel processing is enabled
        if (pipelineConfig.isEnableParallelProcessing()) {
            pipelineExecutor = Executors.newFixedThreadPool(pipelineConfig.getProcessingThreads());
            batchScheduler = Executors.newScheduledThreadPool(1);

            // Schedule periodic batch processing
            batchScheduler.scheduleAtFixedRate(
                this::processPendingBodiesIfNeeded,
                pipelineConfig.getBatchTimeout().toMillis(),
                pipelineConfig.getBatchTimeout().toMillis(),
                TimeUnit.MILLISECONDS
            );
        }

        // Start initial chain sync
        chainSyncAgent.sendNextMessage();
    }

    private void stopPipelineProcessing() {
        pipelineActive.set(false);

        if (pipelineExecutor != null) {
            pipelineExecutor.shutdown();
            try {
                if (!pipelineExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    pipelineExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                pipelineExecutor.shutdownNow();
            }
            pipelineExecutor = null;
        }

        if (batchScheduler != null) {
            batchScheduler.shutdown();
            try {
                if (!batchScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    batchScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                batchScheduler.shutdownNow();
            }
            batchScheduler = null;
        }

        clearPipelineState();
    }

    private void clearPipelineState() {
        pendingHeaders.clear();
        pendingByronHeaders.clear();
        pendingByronEbHeaders.clear();
        requestTimestamps.clear();
    }

    // ========================================
    // LEGACY SUPPORT METHODS
    // ========================================

    private void resetBlockFetchAgentAndFetchBlock(long slot, String hash) {
        if (log.isDebugEnabled()) {
            log.debug("Rolled to slot: {}, block: {}", slot, hash);
        }

        blockFetchAgent.resetPoints(new Point(slot, hash), new Point(slot, hash));

        if (log.isDebugEnabled())
            log.debug("Trying to fetch block for {}", new Point(slot, hash));
        blockFetchAgent.sendNextMessage();
    }

    private void notifyBlockConsumers(Block block) {
        for (Consumer<Block> consumer : blockConsumers) {
            try {
                consumer.accept(block);
            } catch (Exception e) {
                log.error("Error in block consumer", e);
            }
        }
    }

    // ========================================
    // PUBLIC API METHODS
    // ========================================

    /**
     * Legacy method - maintains backward compatibility
     * Automatically fetches headers and bodies sequentially
     */
    @Override
    public void start(Consumer<Block> consumer) {
        legacyMode = true;
        currentStrategy = PipelineStrategy.SEQUENTIAL;

        if (consumer != null) {
            blockConsumers.add(consumer);
        }

        n2nClient.start();
    }

    /**
     * Start ChainSync only - headers will be received but no bodies fetched
     * Useful for header-only synchronization or when you want to control body fetching manually
     */
    public void startChainSyncOnly(Point from) {
        legacyMode = false;
        currentStrategy = PipelineStrategy.HEADERS_ONLY;

        chainSyncAgent.reset(from);
        n2nClient.start();

        log.info("Started ChainSync-only mode from point: {}", from);
    }

    /**
     * Start BlockFetch only - fetch block bodies for specified range
     * Must be called after connection is established
     */
    public void startBlockFetchOnly(Point from, Point to) {
        if (!n2nClient.isRunning()) {
            throw new IllegalStateException("Connection not established. Call connect() first.");
        }

        legacyMode = false;
        blockFetchAgent.resetPoints(from, to);
        blockFetchAgent.sendNextMessage();

        log.info("Started BlockFetch-only mode from {} to {}", from, to);
    }

    /**
     * Start pipelined sync with full parallelization
     * Headers and bodies are processed independently with configurable pipelining
     */
    public void startPipelinedSync(Point from, PipelineConfig config) {
        legacyMode = false;
        this.pipelineConfig = config;
        config.validate();

        currentStrategy = PipelineStrategy.FULL_PARALLEL;

        chainSyncAgent.reset(from);
        n2nClient.start();

        log.info("Started pipelined sync from point: {} with config: {}", from, config);
    }

    /**
     * Start pipelined sync with default high-performance configuration
     */
    public void startPipelinedSync(Point from) {
        startPipelinedSync(from, PipelineConfig.highPerformanceNodeConfig());
    }

    /**
     * Enable selective body fetching - only fetch bodies for headers that pass the filter
     */
    public void enableSelectiveBodyFetch(Predicate<BlockHeader> filter) {
        this.bodyFetchFilter = filter;
        if (currentStrategy != PipelineStrategy.SELECTIVE_BODIES) {
            currentStrategy = PipelineStrategy.SELECTIVE_BODIES;
            log.info("Enabled selective body fetching");
        }
    }

    /**
     * Manually fetch block bodies for specific points
     * Useful for selective or on-demand body fetching
     */
    public void fetchBlockBodies(List<Point> points) {
        if (!n2nClient.isRunning()) {
            throw new IllegalStateException("Connection not established");
        }

        fetchBatchBodies(points);
    }

    /**
     * Set the pipelining strategy
     */
    public void setPipelineStrategy(PipelineStrategy strategy) {
        this.currentStrategy = strategy;
        log.info("Pipeline strategy changed to: {}", strategy);
    }

    /**
     * Get current pipeline metrics
     */
    public PipelineMetrics getPipelineMetrics() {
        return pipelineMetrics;
    }

    /**
     * Get current pipeline configuration
     */
    public PipelineConfig getPipelineConfig() {
        return pipelineConfig;
    }

    /**
     * Update pipeline configuration (only affects new operations)
     */
    public void updatePipelineConfig(PipelineConfig config) {
        config.validate();
        this.pipelineConfig = config;
        log.info("Pipeline configuration updated");
    }

    // ========================================
    // EXISTING API METHODS (for compatibility)
    // ========================================

    public void addBlockFetchListener(BlockfetchAgentListener listener) {
        if (this.isRunning())
            throw new IllegalStateException("Listener can be added only before start() call");

        if (listener != null)
            blockFetchAgent.addListener(listener);
    }

    public void addChainSyncListener(ChainSyncAgentListener listener) {
        if (this.isRunning())
            throw new IllegalStateException("Listener can be added only before start() call");

        if (listener != null)
            chainSyncAgent.addListener(listener);
    }

    public void addTxSubmissionListener(TxSubmissionListener listener) {
        if (this.isRunning())
            throw new IllegalStateException("Listener can be added only before start() call");

        if (listener != null)
            txSubmissionAgent.addListener(listener);
    }

    public void sendKeepAliveMessage(int cookie) {
        if (n2nClient.isRunning())
            keepAliveAgent.sendKeepAlive(cookie);
    }

    public int getLastKeepAliveResponseCookie() {
        return lastKeepAliveResponseCookie;
    }

    public long getLastKeepAliveResponseTime() {
        return lastKeepAliveResponseTime;
    }

    public void submitTxBytes(byte[] txBytes) {
        var txHash = TransactionUtil.getTxHash(txBytes);
        this.submitTxBytes(txHash, txBytes, CONWAY);
    }

    public void submitTxBytes(String txHash, byte[] txBytes, TxBodyType txBodyType) {
        txSubmissionAgent.enqueueTransaction(txHash, txBytes, txBodyType);
    }

    @Override
    public boolean isRunning() {
        return n2nClient.isRunning();
    }

    @Override
    public void shutdown() {
        stopPipelineProcessing();
        n2nClient.shutdown();
    }

    public void fetch(Point from, Point to) {
        if (!n2nClient.isRunning())
            throw new IllegalStateException("fetch() should be called after start()");

        blockFetchAgent.resetPoints(from, to);
        if (!blockFetchAgent.isDone())
            blockFetchAgent.sendNextMessage();
        else
            log.warn("Agent status is Done. Can't reschedule new points.");
    }

    public void startSync(Point from) {
        if (!n2nClient.isRunning())
            throw new IllegalStateException("startSync() should be called after start()");

        //This was missing earlier, so reset the chainSyncAgent to start from the given point
        chainSyncAgent.reset(from);

        chainSyncAgent.sendNextMessage();
        log.info("Starting sync from current point or intersection");
    }

    public void enableTxSubmission() {
        txSubmissionAgent.sendNextMessage();
    }

    // ========================================
    // EXAMPLE USAGE
    // ========================================

    public static void main(String[] args) throws Exception {
//        // Example 1: Simple application (legacy mode)
//        N2NPeerFetcher simpleFetcher = new N2NPeerFetcher("localhost", 32000,
//                Constants.WELL_KNOWN_PREPROD_POINT, Constants.PREPROD_PROTOCOL_MAGIC);
//
//        simpleFetcher.start(block -> {
//            log.info("Simple mode - Block: {}", block.getHeader().getHeaderBody().getBlockNumber());
//        });
//
//        // Example 2: Node implementation with pipelining
//        N2NPeerFetcher nodeFetcher = new N2NPeerFetcher("localhost", 32000,
//                Constants.WELL_KNOWN_PREPROD_POINT, Constants.PREPROD_PROTOCOL_MAGIC);
//
//        PipelineConfig nodeConfig = PipelineConfig.highPerformanceNodeConfig();
//        nodeFetcher.startPipelinedSync(Constants.WELL_KNOWN_PREPROD_POINT, nodeConfig);
//
//        // Example 3: Headers-only sync
        N2NPeerFetcher headerFetcher = new N2NPeerFetcher("localhost", 32000,
                Constants.WELL_KNOWN_PREPROD_POINT, Constants.PREPROD_PROTOCOL_MAGIC);

        headerFetcher.addChainSyncListener(new ChainSyncAgentListener() {
            @Override
            public void rollforward(Tip tip, BlockHeader blockHeader) {
                log.info("Header-only - Block: {}", blockHeader.getHeaderBody().getBlockNumber());
            }
        });

        headerFetcher.startChainSyncOnly(Constants.WELL_KNOWN_PREPROD_POINT);
    }
}
