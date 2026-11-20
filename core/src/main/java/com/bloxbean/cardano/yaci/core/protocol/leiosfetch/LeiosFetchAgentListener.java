package com.bloxbean.cardano.yaci.core.protocol.leiosfetch;

import com.bloxbean.cardano.yaci.core.protocol.AgentListener;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosPoint;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosRawCbor;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosTxBitmap;

public interface LeiosFetchAgentListener extends AgentListener {
    default void onBlock(LeiosPoint requestedPoint, LeiosRawCbor endorserBlock) {
    }

    /**
     * Transactions for an outstanding {@code MsgLeiosBlockTxsRequest}. The reply carries only the transactions,
     * so the requested point and bitmap identify which EB and which transactions they are.
     */
    default void onBlockTxs(LeiosPoint requestedPoint, LeiosTxBitmap requestedBitmap, LeiosRawCbor txList) {
    }

    default void onFetchError(Throwable error) {
    }

    default void onFetchError(LeiosPoint requestedPoint, Throwable error) {
        onFetchError(error);
    }
}
