package com.bloxbean.cardano.yaci.core.protocol.blockfetch;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.byron.ByronBlock;
import com.bloxbean.cardano.yaci.core.protocol.AgentListener;

public interface BlockfetchAgentListener extends AgentListener {
    default void batchStarted() {

    }

    default void batchDone() {

    }

    default void readyForNextBatch() {

    }

    default void blockFound(Block block) {

    }

    default void noBlockFound() {

    }

    default void byronBlockFound(ByronBlock byronBlock) {

    }

}
