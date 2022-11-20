package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.helpers.BlockFetcher;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import com.bloxbean.cardano.yaci.helper.listener.BlockFetchAgentListenerAdapter;

public class BlockRangeSync {
    private String host;
    private int port;
    private long protocolMagic;

    private BlockFetcher blockFetcher;

    public BlockRangeSync(String host, int port, long protocolMagic) {
        this.host = host;
        this.port = port;
        this.protocolMagic = protocolMagic;
    }

    public void start(BlockChainDataListener blockChainDataListener) {
        blockFetcher = new BlockFetcher(host, port, protocolMagic);

        BlockFetchAgentListenerAdapter blockfetchAgentListener = new BlockFetchAgentListenerAdapter(blockChainDataListener);
        blockFetcher.addBlockFetchListener(blockfetchAgentListener);
        blockFetcher.start();
    }

    public void restart(BlockChainDataListener blockChainDataListener) {
        if (blockFetcher != null && blockFetcher.isRunning())
            blockFetcher.shutdown();
        start(blockChainDataListener);
    }

    public void fetch(Point from, Point to) {
        if (blockFetcher == null)
            throw new IllegalStateException("Please call start before fetch");

//        if (from == Point.ORIGIN)
//            from =

        blockFetcher.fetch(from, to);
    }

    public void stop() {
        blockFetcher.shutdown();
    }
}
