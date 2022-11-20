package com.bloxbean.cardano.yaci.helper.listener;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbBlock;
import com.bloxbean.cardano.yaci.core.model.byron.ByronMainBlock;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.helper.model.Transaction;

import java.util.List;

public interface BlockChainDataListener {
    default void onByronBlock(ByronMainBlock byronBlock) {}
    default void onByronEbBlock(ByronEbBlock byronEbBlock) {}

    default void onBlock(Block block) {}
    default void onRollback(Point point) {}
    default void onTransactions(Era era, BlockHeader blockHeader, List<Transaction> transactionEvent) {}
    default void batchDone() {}
}
