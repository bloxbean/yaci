package com.bloxbean.cardano.yaci.core.protocol.appchainsync;

import com.bloxbean.cardano.yaci.core.protocol.AgentListener;

import java.util.List;

public interface AppChainSyncListener extends AgentListener {

    /**
     * Client-side: blocks for the requested range (possibly empty) and the
     * server's current tip height.
     */
    default void blocksReceived(List<byte[]> blocks, long serverTipHeight) {
    }
}
