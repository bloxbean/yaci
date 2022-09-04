package com.bloxbean.cardano.yaci.core.helpers;

import com.bloxbean.cardano.yaci.core.helpers.api.Fetcher;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.byron.ByronHead;
import com.bloxbean.cardano.yaci.core.network.N2CClient;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgent;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.n2c.LocalChainSyncAgent;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.n2c.LocalChainSyncAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.n2n.ChainSyncAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.n2n.ChainsyncAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Consumer;

/**
 * Use this fetcher to fetch blocks from the current tip or from a wellknown point. The fetcher connects to a local
 * Cardano node through node socket file using node-to-client mini-protocol.
 * The block can be received by passing a {@link Consumer} to the start method.
 * The following listeners can be added to receive various events from the agents
 * 1. {@link BlockfetchAgentListener} - To listen to events published by {@link BlockfetchAgent}
 * 2. {@link ChainSyncAgentListener} - To listen to events published by {@link ChainsyncAgent}
 */
@Slf4j
public class N2CChainSyncFetcher implements Fetcher<Block> {
    private String nodeSocketFile;
    private VersionTable versionTable;
    private Point wellKnownPoint;
    private boolean syncFromLatest;
    private boolean tipFound = false;
    private HandshakeAgent handshakeAgent;
    private LocalChainSyncAgent chainSyncAgent;
    private N2CClient n2CClient;

    /**
     * Use this constructor to sync block from the tip.
     *
     * @param nodeSocketFile
     * @param versionTable
     * @param wellKnownPoint
     */
    public N2CChainSyncFetcher(String nodeSocketFile, VersionTable versionTable, Point wellKnownPoint) {
        this(nodeSocketFile, versionTable, wellKnownPoint, true);
    }

    /**
     * Use this constructor to sync blocks from the tip or from a well known point.
     *
     * @param nodeSocketFile path to local Cardano node's socket file
     * @param versionTable
     * @param wellKnownPoint
     * @param syncFromLatest true if sync from tip, false if sync from wellKnownPoint
     */
    public N2CChainSyncFetcher(String nodeSocketFile, VersionTable versionTable, Point wellKnownPoint, boolean syncFromLatest) {
        this.nodeSocketFile = nodeSocketFile;
        this.versionTable = versionTable;
        this.wellKnownPoint = wellKnownPoint;
        this.syncFromLatest = syncFromLatest;

        init();
    }

    private void init() {
        handshakeAgent = new HandshakeAgent(versionTable);
        chainSyncAgent = new LocalChainSyncAgent(new Point[]{wellKnownPoint});

        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                //start
                chainSyncAgent.sendNextMessage();
            }
        });

        chainSyncAgent.addListener(new LocalChainSyncAgentListener() {
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
            public void rollforward(Tip tip, Block block) {
                long slot = block.getHeader().getHeaderBody().getSlot();
                String hash = block.getHeader().getHeaderBody().getBlockHash();

                if (log.isDebugEnabled()) {
                    log.debug("Rolled to slot: {}, block: {}", slot, block.getHeader().getHeaderBody().getBlockNumber());
                }

                if (log.isDebugEnabled())
                    log.debug("Trying to fetch block for {}", new Point(slot, hash));
                chainSyncAgent.sendNextMessage();
            }

            @Override
            public void rollforwardByronEra(Tip tip, ByronHead byronHead) {
                if (log.isDebugEnabled())
                    log.debug("ByronEraBlock : {}", byronHead.getConsensusData().getSlotId());
                chainSyncAgent.sendNextMessage();
            }

            @Override
            public void rollbackward(Tip tip, Point toPoint) {
                chainSyncAgent.sendNextMessage();
            }
        });

        n2CClient = new N2CClient(nodeSocketFile, handshakeAgent,
                chainSyncAgent);
    }

    @Override
    public void start(Consumer<Block> consumer) {
        chainSyncAgent.addListener(new LocalChainSyncAgentListener() {
            @Override
            public void rollforward(Tip tip, Block block) {
                consumer.accept(block);
            }
        });

        n2CClient.start();
    }

    public void addChainSyncListener(LocalChainSyncAgentListener listener) {
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

}
