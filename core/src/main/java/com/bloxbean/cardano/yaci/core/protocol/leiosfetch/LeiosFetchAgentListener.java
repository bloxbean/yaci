package com.bloxbean.cardano.yaci.core.protocol.leiosfetch;

import com.bloxbean.cardano.yaci.core.protocol.AgentListener;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosPoint;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosRawCbor;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosTxBitmap;

public interface LeiosFetchAgentListener extends AgentListener {
    default void onBlock(LeiosPoint requestedPoint, LeiosRawCbor endorserBlock) {
    }

    default void onBlockTxs(LeiosPoint requestedPoint, LeiosPoint responsePoint,
                            LeiosTxBitmap responseBitmap, LeiosRawCbor txList) {
    }

    default void onFetchError(Throwable error) {
    }

    default void onFetchError(LeiosPoint requestedPoint, Throwable error) {
        onFetchError(error);
    }
}
