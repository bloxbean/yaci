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
    private LeiosConfig leiosConfig;

    private BlockFetcher blockFetcher;

    /**
     * Construct a {@link BlockRangeSync} instance
     * @param host Cardano node host
     * @param port Cardano node port
     * @param protocolMagic protocol magic
     */
    public BlockRangeSync(String host, int port, long protocolMagic) {
        this(host, port, protocolMagic, LeiosConfig.defaultConfig());
    }

    /**
     * Construct a range sync with optional Leios policy.
     * In {@link LeiosConfig.Mode#AUTO}, Leios agents stay detached because notify/fetch events are near-tip and
     * not scoped to the requested range. Use {@link LeiosConfig.Mode#ENABLED} to opt in explicitly.
     */
    public BlockRangeSync(String host, int port, long protocolMagic, LeiosConfig leiosConfig) {
        this.host = host;
        this.port = port;
        this.protocolMagic = protocolMagic;
        this.leiosConfig = leiosConfig != null ? leiosConfig : LeiosConfig.defaultConfig();
    }

    /**
     * Establish the connection and start the mini-protocol
     * @param blockChainDataListener
     */
    public void start(BlockChainDataListener blockChainDataListener) {
        blockFetcher = new BlockFetcher(host, port, protocolMagic, leiosConfig);

        BlockFetchAgentListenerAdapter blockfetchAgentListener = new BlockFetchAgentListenerAdapter(blockChainDataListener);
        blockFetcher.addBlockFetchListener(blockfetchAgentListener);
        blockFetcher.addLeiosDataListener(blockChainDataListener);
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

    /**
     * Send keep alive message
     * @param cookie
     */
    public void sendKeepAliveMessage(int cookie) {
        blockFetcher.sendKeepAliveMessage(cookie);
    }

    /**
     * Get the last keep alive response cookie
     * @return
     */
    public int getLastKeepAliveResponseCookie() {
        return blockFetcher.getLastKeepAliveResponseCookie();
    }

    /**
     * Get the last keep alive response time
     * @return
     */
    public long getLastKeepAliveResponseTime() {
        return blockFetcher.getLastKeepAliveResponseTime();
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
