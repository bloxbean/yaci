package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionData;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import com.bloxbean.cardano.yaci.helper.listener.BlockFetchAgentListenerAdapter;
import com.bloxbean.cardano.yaci.helper.listener.ChainSyncListenerAdapter;

import java.util.function.Supplier;

/**
 * A high level helper class to sync blockchain data from tip or from a particular point using node-to-node miniprotocol
 * and receive in a {@link BlockChainDataListener} instance.
 * <p>
 * Leios (experimental): a block that certifies an endorser block is delivered complete, with the endorser block's
 * transactions first, as {@link LeiosConfig} describes. Every other block is delivered as received.
 */
public class BlockSync {
    private String host;
    private int port;
    private Point wellKnownPoint;
    private VersionTable versionTable;
    private final SyncControl<N2NChainSyncFetcher> control;

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
     * @param protocolMagic Protocol magic
     * @param wellKnownPoint A wellknown point
     * @param leiosConfig Leios settings, see {@link LeiosConfig}
     */
    public BlockSync(String host, int port, long protocolMagic, Point wellKnownPoint, LeiosConfig leiosConfig) {
        this(host, port, wellKnownPoint, N2NVersionTableConstant.v4AndAbove(protocolMagic), leiosConfig);
    }

    /**
     * Construct a BlockSync instance
     * @param host Cardano node host
     * @param port Cardano node port
     * @param wellKnownPoint A wellknown point
     * @param versionTable {@link VersionTable} instance
     */
    public BlockSync(String host, int port, Point wellKnownPoint, VersionTable versionTable) {
        this(host, port, wellKnownPoint, versionTable, LeiosConfig.defaultConfig());
    }

    /**
     * Construct a BlockSync instance
     * @param host Cardano node host
     * @param port Cardano node port
     * @param wellKnownPoint A wellknown point
     * @param versionTable {@link VersionTable} instance
     * @param leiosConfig Leios settings, see {@link LeiosConfig}
     */
    public BlockSync(String host, int port, Point wellKnownPoint, VersionTable versionTable, LeiosConfig leiosConfig) {
        this.host = host;
        this.port = port;
        this.wellKnownPoint = wellKnownPoint;
        this.versionTable = versionTable;

        EbAnnouncementCache cache = new EbAnnouncementCache();
        long fallbackMagic = magicOf(versionTable);
        this.control = new SyncControl<>(leiosConfig, cache,
                owner -> {
                    Supplier<PeerAddress> peer = () -> mainPeer(owner.get(), host, port);
                    return new EndorserBlockResolver(peer, () -> mainMagic(owner.get(), fallbackMagic), cache,
                            new ParentBlockLookup(peer, versionTable));
                },
                CompleteBlockAssembler.BUDGET_MILLIS, CompleteBlockAssembler.PAUSE_MILLIS, SyncControl::runOnNewThread,
                0); //BlockSync keeps the synchronous path (ADR 0017 D4)
    }

    /**
     * Start sync from a given point
     * @param point point to start sync from
     * @param blockChainDataListener {@link BlockChainDataListener} instance
     */
    public void startSync(Point point, BlockChainDataListener blockChainDataListener) {
        initializeAgentAndStart(point, blockChainDataListener, false);
    }

    private void initializeAgentAndStart(Point point, BlockChainDataListener blockChainDataListener, boolean syncFromTip) {
        control.startNew(blockChainDataListener, listener -> {
            N2NChainSyncFetcher fetcher = new N2NChainSyncFetcher(host, port, point, versionTable, syncFromTip);

            BlockFetchAgentListenerAdapter blockfetchAgentListener = new BlockFetchAgentListenerAdapter(listener);
            ChainSyncListenerAdapter chainSyncAgentListener = new ChainSyncListenerAdapter(listener);
            fetcher.addChainSyncListener(chainSyncAgentListener);
            fetcher.addBlockFetchListener(blockfetchAgentListener);
            return fetcher;
        });
    }

    /**
     * Start sync from tip
     * @param blockChainDataListener {@link BlockChainDataListener} instance
     */
    public void startSyncFromTip(BlockChainDataListener blockChainDataListener) {
        initializeAgentAndStart(wellKnownPoint, blockChainDataListener, true);
    }

    /**
     * Send keep alive message
     * @param cookie
     */
    public void sendKeepAliveMessage(int cookie) {
        N2NChainSyncFetcher fetcher = control.client();
        if (fetcher.isRunning())
            fetcher.sendKeepAliveMessage(cookie);
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

    private static PeerAddress mainPeer(N2NChainSyncFetcher fetcher, String host, int port) {
        return fetcher != null ? fetcher.mainPeer() : new PeerAddress(host, port);
    }

    private static long mainMagic(N2NChainSyncFetcher fetcher, long fallbackMagic) {
        AcceptVersion accepted = fetcher != null ? fetcher.getAcceptedVersion() : null;
        return accepted != null && accepted.getVersionData() != null
                ? accepted.getVersionData().getNetworkMagic() : fallbackMagic;
    }

    static long magicOf(VersionTable versionTable) {
        if (versionTable == null || versionTable.getVersionDataMap() == null)
            return 0;
        for (VersionData data : versionTable.getVersionDataMap().values()) {
            if (data != null)
                return data.getNetworkMagic();
        }
        return 0;
    }
}
