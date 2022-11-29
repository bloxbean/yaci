package com.bloxbean.cardano.yaci.core.protocol.localtxmonitor;

import com.bloxbean.cardano.yaci.core.protocol.AgentListener;
import com.bloxbean.cardano.yaci.core.protocol.localtxmonitor.messages.*;

public interface LocalTxMonitorListener extends AgentListener {
    default void acquiredAt(long slot) {

    }

    default void onReplyHashTx(MsgHasTx request, MsgReplyHasTx reply) {

    }

    default void onReplyNextTx(MsgNextTx request, MsgReplyNextTx reply) {

    }

    default void onReplyGetSizes(MsgGetSizes request, MsgReplyGetSizes reply) {

    }
}
