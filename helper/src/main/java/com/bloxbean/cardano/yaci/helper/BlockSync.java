package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.helpers.N2NChainSyncFetcher;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import com.bloxbean.cardano.yaci.helper.listener.BlockFetchAgentListenerAdapter;
import com.bloxbean.cardano.yaci.helper.listener.ChainSyncListenerAdapter;

public class BlockSync {
    private String host;
    private int port;
    private long protocolMagic;
    private Point wellKnownPoint;

    private N2NChainSyncFetcher n2NChainSyncFetcher;

    public BlockSync(String host, int port, long protocolMagic, Point wellKnownPoint) {
        this.host = host;
        this.port = port;
        this.protocolMagic = protocolMagic;
        this.wellKnownPoint = wellKnownPoint;
    }

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

//    public void restartSync(Point point, BlockChainDataListener transactionListener) {
//        if (n2NChainSyncFetcher != null && n2NChainSyncFetcher.isRunning())
//            n2NChainSyncFetcher.shutdown();
//        startSync(point, transactionListener);
//    }

    public void startSyncFromTip(BlockChainDataListener blockChainDataListener) {

        if (n2NChainSyncFetcher != null && n2NChainSyncFetcher.isRunning())
            n2NChainSyncFetcher.shutdown();

        initializeAgentAndStart(wellKnownPoint, blockChainDataListener, true);
//
////        TipFinder tipFinder = new TipFinder(host, port, wellKnownPoint, protocolMagic);
////        Mono<Tip> tipMono = tipFinder.find();
//
////        Tip tip = tipMono.block();
//        startSync(tip.getPoint(), blockChainDataListener);
    }

    public void stop() {
        n2NChainSyncFetcher.shutdown();
    }

}
