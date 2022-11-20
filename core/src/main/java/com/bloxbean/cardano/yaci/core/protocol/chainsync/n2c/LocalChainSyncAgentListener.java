package com.bloxbean.cardano.yaci.core.protocol.chainsync.n2c;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbBlock;
import com.bloxbean.cardano.yaci.core.model.byron.ByronMainBlock;
import com.bloxbean.cardano.yaci.core.protocol.AgentListener;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;

public interface LocalChainSyncAgentListener extends AgentListener {

    default void intersactFound(Tip tip, Point point) {

    }

    default void intersactNotFound(Tip tip) {

    }

    default void rollforward(Tip tip, Block block) {

    }

    default void rollbackward(Tip tip, Point toPoint) {

    }

    default void rollforwardByronEra(Tip tip, ByronMainBlock byronMainBlock) {

    }

    default void rollforwardByronEra(Tip tip, ByronEbBlock byronEbBlock) {

    }
}
