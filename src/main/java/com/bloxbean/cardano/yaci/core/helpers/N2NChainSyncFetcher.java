package com.bloxbean.cardano.yaci.core.helpers;

import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.core.helpers.api.Fetcher;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.byron.ByronBlock;
import com.bloxbean.cardano.yaci.core.model.byron.ByronHead;
import com.bloxbean.cardano.yaci.core.network.N2NClient;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgent;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.n2n.ChainSyncAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.n2n.ChainsyncAgent;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import lombok.extern.slf4j.Slf4j;

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
    private VersionTable versionTable;
    private Point wellKnownPoint;
    private boolean syncFromLatest;
    private boolean tipFound = false;
    private HandshakeAgent handshakeAgent;
    private ChainsyncAgent chainSyncAgent;
    private BlockfetchAgent blockFetchAgent;
    private N2NClient n2CClient;

    public N2NChainSyncFetcher(String host, int port, VersionTable versionTable, Point wellKnownPoint) {
        this(host, port, versionTable, wellKnownPoint, true);
    }

    public N2NChainSyncFetcher(String host, int port, VersionTable versionTable, Point wellKnownPoint, boolean syncFromLatest) {
        this.host = host;
        this.port = port;
        this.versionTable = versionTable;
        this.wellKnownPoint = wellKnownPoint;
        this.syncFromLatest = syncFromLatest;

        init();
    }

    private void init() {
        handshakeAgent = new HandshakeAgent(versionTable);
        chainSyncAgent = new ChainsyncAgent(new Point[]{wellKnownPoint});
        blockFetchAgent = new BlockfetchAgent();
        blockFetchAgent.resetPoints(wellKnownPoint, wellKnownPoint);

        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                //start
                chainSyncAgent.sendNextMessage();
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

                if (log.isDebugEnabled()) {
                    log.debug("Rolled to slot: {}, block: {}", blockHeader.getHeaderBody().getSlot(), blockHeader.getHeaderBody().getBlockNumber());
                }

                blockFetchAgent.resetPoints(new Point(slot, hash), new Point(slot, hash));

                if (log.isDebugEnabled())
                    log.debug("Trying to fetch block for {}", new Point(slot, hash));
                blockFetchAgent.sendNextMessage();
            }

            @Override
            public void rollforwardByronEra(Tip tip, ByronHead byronHead) {
                chainSyncAgent.sendNextMessage(); //TODO -- For now. Remove after Byron block parsing
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
                chainSyncAgent.sendNextMessage();
            }

            @Override
            public void byronBlockFound(ByronBlock byronBlock) {
                chainSyncAgent.sendNextMessage();
            }
        });

        n2CClient = new N2NClient(host, port, handshakeAgent,
                chainSyncAgent, blockFetchAgent);
    }

    @Override
    public void start(Consumer<Block> consumer) {
        blockFetchAgent.addListener(new BlockfetchAgentListener() {
            @Override
            public void blockFound(Block block) {
                consumer.accept(block);
            }
        });

        n2CClient.start();
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

    @Override
    public boolean isRunning() {
        return n2CClient.isRunning();
    }

    @Override
    public void shutdown() {
        n2CClient.shutdown();
    }

    public static void main(String[] args) throws Exception {
        VersionTable versionTable = N2NVersionTableConstant.v4AndAbove(1);
//        Point wellKnownPoint = new Point(16588737, "4e9bbbb67e3ae262133d94c3da5bffce7b1127fc436e7433b87668dba34c354a");
//        N2NChainSyncFetcher chainSyncFetcher = new N2NChainSyncFetcher("192.168.0.228", 6000, versionTable, wellKnownPoint);
        N2NChainSyncFetcher chainSyncFetcher = new N2NChainSyncFetcher("localhost", 30000, versionTable, Constants.WELL_KNOWN_PREPOD_POINT, false);

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
