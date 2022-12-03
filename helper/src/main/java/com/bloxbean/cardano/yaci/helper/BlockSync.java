package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
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
    private long protocolMagic;
    private Point wellKnownPoint;

    private N2NChainSyncFetcher n2NChainSyncFetcher;

    /**
     * Construct a BlockSync instance
     * @param host Cardano node host
     * @param port Cardano node port
     * @param protocolMagic Protocol magic
     * @param wellKnownPoint A wellknown point
     */
    public BlockSync(String host, int port, long protocolMagic, Point wellKnownPoint) {
        this.host = host;
        this.port = port;
        this.protocolMagic = protocolMagic;
        this.wellKnownPoint = wellKnownPoint;
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
        n2NChainSyncFetcher = new N2NChainSyncFetcher(host, port, point, protocolMagic, false);

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
