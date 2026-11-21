package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import com.bloxbean.cardano.yaci.helper.listener.BlockFetchAgentListenerAdapter;

import java.util.function.Supplier;

/**
 * A high level helper class to fetch blockchain data from point1 to point2. This class uses node-to-no mini protocol to fetch blocks.
 * Fetched blocks are received in an instance of {@link BlockChainDataListener}
 * <p>
 * Leios (experimental): a block that certifies an endorser block is delivered complete, with the endorser block's
 * transactions first, as {@link LeiosConfig} describes. Every other block is delivered as received. After a halt,
 * resume with {@link #restart(BlockChainDataListener)} then {@link #fetch(Point, Point)}.
 */
public class BlockRangeSync {
    private String host;
    private int port;
    private long protocolMagic;
    private final SyncControl<BlockFetcher> control;

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
     * Construct a {@link BlockRangeSync} instance
     * @param host Cardano node host
     * @param port Cardano node port
     * @param protocolMagic protocol magic
     * @param leiosConfig Leios settings, see {@link LeiosConfig}
     */
    public BlockRangeSync(String host, int port, long protocolMagic, LeiosConfig leiosConfig) {
        this(host, port, protocolMagic, leiosConfig, CompleteBlockAssembler.BUDGET_MILLIS,
                CompleteBlockAssembler.PAUSE_MILLIS);
    }

    BlockRangeSync(String host, int port, long protocolMagic, LeiosConfig leiosConfig, long budgetMillis,
                   long pauseMillis) {
        this.host = host;
        this.port = port;
        this.protocolMagic = protocolMagic;

        EbAnnouncementCache cache = new EbAnnouncementCache();
        this.control = new SyncControl<>(leiosConfig, cache,
                owner -> {
                    Supplier<PeerAddress> peer = () -> {
                        BlockFetcher fetcher = owner.get();
                        return fetcher != null ? fetcher.mainPeer() : new PeerAddress(host, port);
                    };
                    return new EndorserBlockResolver(peer, () -> mainMagic(owner.get()), cache,
                            new ParentBlockLookup(peer, N2NVersionTableConstant.v4AndAbove(protocolMagic)));
                },
                budgetMillis, pauseMillis, SyncControl::runOnNewThread,
                leiosConfig != null ? leiosConfig.getEndorserBlockReadAhead() : 0);
    }

    /**
     * Establish the connection and start the mini-protocol
     * @param blockChainDataListener
     */
    public void start(BlockChainDataListener blockChainDataListener) {
        control.startNew(blockChainDataListener, listener -> {
            BlockFetcher fetcher = new BlockFetcher(host, port, protocolMagic);

            BlockFetchAgentListenerAdapter blockfetchAgentListener = new BlockFetchAgentListenerAdapter(listener);
            fetcher.addBlockFetchListener(blockfetchAgentListener);
            return fetcher;
        });
    }

    /**
     * Restart with a new listener
     * @param blockChainDataListener
     */
    public void restart(BlockChainDataListener blockChainDataListener) {
        start(blockChainDataListener);
    }

    /**
     * Start to fetch block
     * <p>
     * As on {@link BlockFetcher#fetch}, including from inside {@code batchDone}. Leios (experimental): with
     * {@code LeiosConfig.inlineEndorserBlockTxs} (the default), it throws {@link IllegalStateException}, before
     * anything is sent, after a halt (call {@link #restart(BlockChainDataListener)} then fetch) and while a
     * certifying block of this start waits for its endorser block. Neither happens before Dijkstra.
     * @param from  from point
     * @param to  to point
     */
    public void fetch(Point from, Point to) {
        SyncControl.Start<BlockFetcher> start = control.current();
        if (start == null)
            throw new IllegalStateException("Please call start before fetch");

        if (start.delegate != null)
            start.delegate.checkFetch();
        start.client.fetch(from, to);
    }

    /**
     * Send keep alive message
     * @param cookie
     */
    public void sendKeepAliveMessage(int cookie) {
        control.client().sendKeepAliveMessage(cookie);
    }

    /**
     * Get the last keep alive response cookie
     * @return
     */
    public int getLastKeepAliveResponseCookie() {
        return control.client().getLastKeepAliveResponseCookie();
    }

    /**
     * Get the last keep alive response time
     * @return
     */
    public long getLastKeepAliveResponseTime() {
        return control.client().getLastKeepAliveResponseTime();
    }

    /**
     * Stop the fetcher
     */
    public void stop() {
        control.stop();
    }

    /**
     * Check if the connection is alive. False once the sync halted (Leios, see {@link LeiosConfig}).
     */
    public boolean isRunning() {
        return control.isRunning();
    }

    private long mainMagic(BlockFetcher fetcher) {
        AcceptVersion accepted = fetcher != null ? fetcher.getAcceptedVersion() : null;
        return accepted != null && accepted.getVersionData() != null
                ? accepted.getVersionData().getNetworkMagic() : protocolMagic;
    }
}
