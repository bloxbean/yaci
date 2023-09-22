package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import com.bloxbean.cardano.yaci.helper.listener.BlockFetchAgentListenerAdapter;
import com.bloxbean.cardano.yaci.helper.listener.ChainSyncListenerAdapter;

/**
 * A high level helper class to sync blockchain data from tip or from a particular point using node-to-node miniprotocol
 * and receive in a {@link BlockChainDataListener} instance.
 */
public class BlockSync {
    private String host;
    private int port;
    private Point wellKnownPoint;
    private VersionTable versionTable;

    private N2NChainSyncFetcher n2NChainSyncFetcher;

    /**
     * Construct a BlockSync instance
     * @param host Cardano node host
     * @param port Cardano node port
     * @param protocolMagic Protocol magic
     * @param wellKnownPoint A wellknown point
     */
    public BlockSync(String host, int port, long protocolMagic, Point wellKnownPoint) {
        this(host, port, wellKnownPoint, N2NVersionTableConstant.v4AndAbove(protocolMagic));
    }

    /**
     * Construct a BlockSync instance
     * @param host Cardano node host
     * @param port Cardano node port
     * @param wellKnownPoint A wellknown point
     * @param versionTable {@link VersionTable} instance
     */
    public BlockSync(String host, int port, Point wellKnownPoint, VersionTable versionTable) {
        this.host = host;
        this.port = port;
        this.wellKnownPoint = wellKnownPoint;
        this.versionTable = versionTable;
    }

    /**
     * Start sync from a given point
     * @param point point to start sync from
     * @param blockChainDataListener {@link BlockChainDataListener} instance
     */
    public void startSync(Point point, BlockChainDataListener blockChainDataListener) {
        if (n2NChainSyncFetcher != null && n2NChainSyncFetcher.isRunning())
            n2NChainSyncFetcher.shutdown();

        initializeAgentAndStart(point, blockChainDataListener, false);
    }

    private void initializeAgentAndStart(Point point, BlockChainDataListener blockChainDataListener, boolean syncFromTip) {
        n2NChainSyncFetcher = new N2NChainSyncFetcher(host, port, point, versionTable, syncFromTip);

        BlockFetchAgentListenerAdapter blockfetchAgentListener = new BlockFetchAgentListenerAdapter(blockChainDataListener);
        ChainSyncListenerAdapter chainSyncAgentListener = new ChainSyncListenerAdapter(blockChainDataListener);
        n2NChainSyncFetcher.addChainSyncListener(chainSyncAgentListener);
        n2NChainSyncFetcher.addBlockFetchListener(blockfetchAgentListener);

        n2NChainSyncFetcher.start();
    }

    /**
     * Start sync from tip
     * @param blockChainDataListener {@link BlockChainDataListener} instance
     */
    public void startSyncFromTip(BlockChainDataListener blockChainDataListener) {

        if (n2NChainSyncFetcher != null && n2NChainSyncFetcher.isRunning())
            n2NChainSyncFetcher.shutdown();

        initializeAgentAndStart(wellKnownPoint, blockChainDataListener, true);
    }

    /**
     * Stop the fetcher
     */
    public void stop() {
        n2NChainSyncFetcher.shutdown();
    }

}
