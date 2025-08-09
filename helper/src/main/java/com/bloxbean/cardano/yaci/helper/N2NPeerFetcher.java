package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.core.common.GenesisConfig;
import com.bloxbean.cardano.yaci.core.common.TxBodyType;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.Era;
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

import java.util.function.Consumer;

import static com.bloxbean.cardano.yaci.core.common.TxBodyType.CONWAY;

/**
 * Enhanced fetcher with pipelining support for high-performance blockchain synchronization.
 * Supports both simple applications (backward compatible) and node implementations (with pipelining).
 * <p>
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


    private volatile boolean firstTimeHandshake = true;

    private boolean headersOnlyFetch = false;

    /**
     * Reset the firstTimeHandshake flag on disconnection to prevent duplicate messages
     * on reconnection. This ensures the handshake behavior is consistent across
     * initial connection and reconnections.
     */
    public void resetHandshakeFlag() {
        this.firstTimeHandshake = true;
        log.debug("Reset firstTimeHandshake flag for clean reconnection");
    }

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
                
                // Notify agent about new connection to handle stale responses
                chainSyncAgent.onConnectionEstablished();

                //We don't need to start chain sync here, as it will be started by the application
                //by invoking startXXX() methods.
                if (firstTimeHandshake) {
                    firstTimeHandshake = false;
                    log.info("First time handshake completed. Waiting for explicit sync start.");
                } else {
                    log.info("Reconnection handshake completed. Resuming chain sync...");
                    // On reconnection, we MUST send the next message to continue the protocol
                    // The agent's reset() method now preserves currentPoint, so FindIntersect
                    // will use the last confirmed point, ensuring we continue from where we left off
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
                log.error("IntersectNotFound: {}", tip);
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
            
            @Override
            public void onDisconnect() {
                log.info("ChainSync agent disconnected - resetting handshake flag");
                // Notify agent about connection loss for reconnection preparation
                chainSyncAgent.onConnectionLost();
                // Reset firstTimeHandshake to false so that on reconnection
                // we know it's a reconnection and not the first connection
                firstTimeHandshake = false;
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

    private synchronized void handleRollForward(Tip tip, BlockHeader blockHeader) {
        if (headersOnlyFetch) {
            Point blockPoint = new Point(blockHeader.getHeaderBody().getSlot(), blockHeader.getHeaderBody().getBlockHash());
            chainSyncAgent.confirmBlock(blockPoint);
            chainSyncAgent.sendNextMessage();
        } else {
            resetBlockFetchAgentAndFetchBlock(blockHeader.getHeaderBody().getSlot(),
                    blockHeader.getHeaderBody().getBlockHash());
        }
    }

    private synchronized void handleByronRollForward(Tip tip, ByronBlockHead byronHead) {
        long absoluteSlot = GenesisConfig.getInstance().absoluteSlot(Era.Byron,
                byronHead.getConsensusData().getSlotId().getEpoch(),
                byronHead.getConsensusData().getSlotId().getSlot());
        if (headersOnlyFetch) {
            Point blockPoint = new Point(absoluteSlot, byronHead.getBlockHash());
            chainSyncAgent.confirmBlock(blockPoint);
            chainSyncAgent.sendNextMessage();
        } else {
            resetBlockFetchAgentAndFetchBlock(absoluteSlot,
                    byronHead.getBlockHash());
        }
    }

    private synchronized void handleByronEbRollForward(Tip tip, ByronEbHead byronEbHead) {
        long absoluteSlot = GenesisConfig.getInstance().absoluteSlot(Era.Byron,
                byronEbHead.getConsensusData().getEpoch(),
                0);
        if (headersOnlyFetch) {
            Point blockPoint = new Point(absoluteSlot, byronEbHead.getBlockHash());
            chainSyncAgent.confirmBlock(blockPoint);
            chainSyncAgent.sendNextMessage();
        } else {
            resetBlockFetchAgentAndFetchBlock(absoluteSlot,
                    byronEbHead.getBlockHash());
        }
    }

    private synchronized void handleRollbackward(Tip tip, Point toPoint) {
        log.info("Rollback to point: {}", toPoint);
        chainSyncAgent.sendNextMessage();
    }

    // ========================================
    // BLOCK FOUND HANDLING
    // ========================================

    private void handleBlockFound(Block block) {
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

    private void handleByronBlockFound(ByronMainBlock byronBlock) {
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

    private void handleByronEbBlockFound(ByronEbBlock byronEbBlock) {
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


    private void resetBlockFetchAgentAndFetchBlock(long slot, String hash) {
        if (log.isDebugEnabled()) {
            log.debug("Rolled to slot: {}, block: {}", slot, hash);
        }

        blockFetchAgent.resetPoints(new Point(slot, hash), new Point(slot, hash));

        if (log.isDebugEnabled())
            log.debug("Trying to fetch block for {}", new Point(slot, hash));
        blockFetchAgent.sendNextMessage();
    }

    // ========================================
    // PUBLIC API METHODS
    // ========================================

    /**
     * Automatically fetches headers and bodies sequentially
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
     * Start ChainSync only - headers will be received but no bodies fetched
     * Useful for header-only synchronization or when you want to control body fetching manually
     */
    public void startChainSyncOnly(Point from, boolean isPipelined) {
        if (!n2nClient.isRunning()) {
            throw new IllegalStateException("Connection not established. Call connect() first.");
        }

        headersOnlyFetch = true;
        chainSyncAgent.enablePipelining(isPipelined);

        log.info("Started ChainSync-only mode from point: {}", from);
        chainSyncAgent.reset(from);
        chainSyncAgent.sendNextMessage();
    }

    /**
     * Start BlockFetch only - fetch block bodies for specified range
     * Must be called after connection is established
     */
    public void startBlockFetchOnly(Point from, Point to) {
        if (!n2nClient.isRunning()) {
            throw new IllegalStateException("Connection not established. Call connect() first.");
        }

        blockFetchAgent.resetPoints(from, to);
        blockFetchAgent.sendNextMessage();

        log.info("Started BlockFetch-only mode from {} to {}", from, to);
    }

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
        N2NPeerFetcher headerFetcher = new N2NPeerFetcher("localhost", 32000,
                Constants.WELL_KNOWN_PREPROD_POINT, Constants.PREPROD_PROTOCOL_MAGIC);

        headerFetcher.addChainSyncListener(new ChainSyncAgentListener() {
            @Override
            public void rollforward(Tip tip, BlockHeader blockHeader) {
                log.info("Header-only - Block: {}", blockHeader.getHeaderBody().getBlockNumber());
            }
        });

        headerFetcher.startChainSyncOnly(Constants.WELL_KNOWN_PREPROD_POINT, true);
    }
}
