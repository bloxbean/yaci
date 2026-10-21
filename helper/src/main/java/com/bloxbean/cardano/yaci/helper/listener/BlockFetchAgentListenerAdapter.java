package com.bloxbean.cardano.yaci.helper.listener;

import com.bloxbean.cardano.yaci.core.exception.BlockParseRuntimeException;
import com.bloxbean.cardano.yaci.core.model.*;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbBlock;
import com.bloxbean.cardano.yaci.core.model.byron.ByronMainBlock;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import lombok.extern.slf4j.Slf4j;


@Slf4j
public class BlockFetchAgentListenerAdapter implements BlockfetchAgentListener {
    private BlockChainDataListener blockChainDataListener;

    public BlockFetchAgentListenerAdapter(BlockChainDataListener blockChainDataListener) {
        this.blockChainDataListener = blockChainDataListener;
    }

    @Override
    public void blockFound(Block block) {
        blockChainDataListener.onBlock(block.getEra(), block, TransactionListBuilder.build(block));
    }

    @Override
    public void byronBlockFound(ByronMainBlock byronBlock) {
        blockChainDataListener.onByronBlock(byronBlock);
    }

    @Override
    public void byronEbBlockFound(ByronEbBlock byronEbBlock) {
        blockChainDataListener.onByronEbBlock(byronEbBlock);
    }

    @Override
    public void batchStarted() {
        blockChainDataListener.batchStarted();
    }

    @Override
    public void batchDone() {
        blockChainDataListener.batchDone();
    }

    @Override
    public void noBlockFound(Point from, Point to) {
        blockChainDataListener.noBlockFound(from, to);
    }

    @Override
    public void onDisconnect() {
        blockChainDataListener.onDisconnect();
    }

    @Override
    public void onParsingError(BlockParseRuntimeException e) {
        blockChainDataListener.onParsingError(e);
    }
}
