package com.bloxbean.cardano.yaci.core.protocol.blockfetch;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbBlock;
import com.bloxbean.cardano.yaci.core.model.byron.ByronMainBlock;
import com.bloxbean.cardano.yaci.core.protocol.AgentListener;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;

public interface BlockfetchAgentListener extends AgentListener {
    default void batchStarted() {

    }

    default void batchDone() {

    }

    default void readyForNextBatch() {

    }

    default void blockFound(Block block) {

    }

    default void noBlockFound(Point from, Point to) {

    }

    default void byronBlockFound(ByronMainBlock byronBlock) {

    }

    default void byronEbBlockFound(ByronEbBlock byronEbBlock) {

    }

}
