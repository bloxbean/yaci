package com.bloxbean.cardano.yaci.helper.listener;

import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.byron.ByronBlockHead;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbHead;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.n2n.ChainSyncAgentListener;

public class ChainSyncListenerAdapter implements ChainSyncAgentListener {
    private BlockChainDataListener blockChainDataListener;

    public ChainSyncListenerAdapter(BlockChainDataListener blockChainDataListener) {
        this.blockChainDataListener = blockChainDataListener;
    }

    public void intersactFound(Tip tip, Point point) {
        blockChainDataListener.intersactFound(tip, point);
    }

    public void intersactNotFound(Tip tip) {
        blockChainDataListener.intersactNotFound(tip);
    }

    public void rollforward(Tip tip, BlockHeader blockHeader) {

    }

    public void rollbackward(Tip tip, Point toPoint) {
        blockChainDataListener.onRollback(toPoint);
    }

    @Override
    public void rollforwardByronEra(Tip tip, ByronEbHead byronEbHead) {

    }

    @Override
    public void rollforwardByronEra(Tip tip, ByronBlockHead byronBlockHead) {

    }
}
