package com.bloxbean.cardano.yaci.helper.listener;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbBlock;
import com.bloxbean.cardano.yaci.core.model.byron.ByronMainBlock;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.helper.model.Transaction;

import java.util.List;

public interface BlockChainDataListener {
    default void onByronBlock(ByronMainBlock byronBlock) {}
    default void onByronEbBlock(ByronEbBlock byronEbBlock) {}

    default void onBlock(Block block) {}
    default void onRollback(Point point) {}
    default void onTransactions(Era era, BlockHeader blockHeader, List<Transaction> transactions) {}

    default void batchDone() {}
    default void noBlockFound(Point from, Point to) {}

    /**
     * Called when an intersection is found. This is available only for {@link com.bloxbean.cardano.yaci.helper.BlockSync} (Block sync protocol)
     * @param tip
     * @param point
     */
    default void intersactFound(Tip tip, Point point) {}

    /**
     * Called when an intersection is not found. This is available only for {@link com.bloxbean.cardano.yaci.helper.BlockSync} (Block sync protocol)
     * @param tip
     */
    default void intersactNotFound(Tip tip) {}
}
