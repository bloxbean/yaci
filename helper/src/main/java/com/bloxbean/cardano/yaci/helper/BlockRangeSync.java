package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import com.bloxbean.cardano.yaci.helper.listener.BlockFetchAgentListenerAdapter;

/**
 * A high level helper class to fetch blockchain data from point1 to point2. This class uses node-to-no mini protocol to fetch blocks.
 * Fetched blocks are received in an instance of {@link BlockChainDataListener}
 */
public class BlockRangeSync {
    private String host;
    private int port;
    private long protocolMagic;

    private BlockFetcher blockFetcher;

    /**
     * Construct a {@link BlockRangeSync} instance
     * @param host Cardano node host
     * @param port Cardano node port
     * @param protocolMagic protocol magic
     */
    public BlockRangeSync(String host, int port, long protocolMagic) {
        this.host = host;
        this.port = port;
        this.protocolMagic = protocolMagic;
    }

    /**
     * Establish the connection and start the mini-protocol
     * @param blockChainDataListener
     */
    public void start(BlockChainDataListener blockChainDataListener) {
        blockFetcher = new BlockFetcher(host, port, protocolMagic);

        BlockFetchAgentListenerAdapter blockfetchAgentListener = new BlockFetchAgentListenerAdapter(blockChainDataListener);
        blockFetcher.addBlockFetchListener(blockfetchAgentListener);
        blockFetcher.start();
    }

    /**
     * Restart with a new listener
     * @param blockChainDataListener
     */
    public void restart(BlockChainDataListener blockChainDataListener) {
        if (blockFetcher != null && blockFetcher.isRunning())
            blockFetcher.shutdown();
        start(blockChainDataListener);
    }

    /**
     * Start to fetch block
     * @param from  from point
     * @param to  to point
     */
    public void fetch(Point from, Point to) {
        if (blockFetcher == null)
            throw new IllegalStateException("Please call start before fetch");

        blockFetcher.fetch(from, to);
    }

    public void sendKeepAliveMessage(int cookie) {
        blockFetcher.sendKeepAliveMessage(cookie);
    }

    /**
     * Stop the fetcher
     */
    public void stop() {
        blockFetcher.shutdown();
    }

    /**
     * Check if the connection is alive
     */
    public boolean isRunning() {
        return blockFetcher.isRunning();
    }
}
