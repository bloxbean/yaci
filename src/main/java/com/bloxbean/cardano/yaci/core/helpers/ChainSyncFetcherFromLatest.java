package com.bloxbean.cardano.yaci.core.helpers;

import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.yaci.core.helpers.api.Fetcher;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.network.N2NClient;
import com.bloxbean.cardano.yaci.core.protocol.State;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgent;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.ChainSyncAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.ChainsyncAgent;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Consumer;

/**
 * Use this fetcher to fetch blocks from the current tip.
 * The block can be received by passing a {@link Consumer} to the start method.
 * The following listeners can be added to receive various events from the agents
 * 1. {@link BlockfetchAgentListener} - To listen to events published by {@link BlockfetchAgent}
 * 2. {@link ChainSyncAgentListener} - To listen to events published by {@link ChainsyncAgent}
 */
@Slf4j
public class ChainSyncFetcherFromLatest implements Fetcher<Block> {
    private String host;
    private int port;
    private VersionTable versionTable;
    private Point wellKnownPoint;
    private boolean tipFound = false;
    private HandshakeAgent handshakeAgent;
    private ChainsyncAgent chainSyncAgent;
    private BlockfetchAgent blockFetch;
    private N2NClient n2CClient;

    public ChainSyncFetcherFromLatest(String host, int port, VersionTable versionTable, Point wellKnownPoint) {
        this.host = host;
        this.port = port;
        this.versionTable = versionTable;
        this.wellKnownPoint = wellKnownPoint;

        init();
    }

    private void init() {
        handshakeAgent = new HandshakeAgent(versionTable);
        chainSyncAgent = new ChainsyncAgent(new Point[]{wellKnownPoint});
        blockFetch = new BlockfetchAgent();
        blockFetch.resetPoints(wellKnownPoint, wellKnownPoint);

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

                if (!tip.getPoint().equals(point) && !tipFound) {
                    chainSyncAgent.reset(tip.getPoint());
                    tipFound = true;
                }
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

                blockFetch.resetPoints(new Point(slot, hash), new Point(slot, hash));

                if (log.isDebugEnabled())
                    log.debug("Trying to fetch block for {}", new Point(slot, hash));
                blockFetch.sendNextMessage();
            }

            @Override
            public void onStateUpdate(State oldState, State newState) {
                chainSyncAgent.sendNextMessage();
            }
        });

        blockFetch.addListener(new BlockfetchAgentListener() {
            @Override
            public void blockFound(Block block) {
                if (log.isDebugEnabled()) {
                    log.debug("Block Found >> " + block);
                }
            }
        });

        n2CClient = new N2NClient(host, port, handshakeAgent,
                chainSyncAgent, blockFetch);
    }

    @Override
    public void start(Consumer<Block> consumer) {
        blockFetch.addListener(new BlockfetchAgentListener() {
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
            blockFetch.addListener(listener);
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
        VersionTable versionTable = N2NVersionTableConstant.v4AndAbove(Networks.mainnet().getProtocolMagic());
        Point wellKnownPoint = new Point(17625824, "765359c702103513dcb8ff4fe86c1a1522c07535733f31ff23231ccd9a3e0247");
        ChainSyncFetcherFromLatest chainSyncFetcher = new ChainSyncFetcherFromLatest("192.168.0.228", 6000, versionTable, wellKnownPoint);

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
