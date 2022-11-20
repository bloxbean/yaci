package com.bloxbean.cardano.yaci.core.protocol.localtx;

import com.bloxbean.cardano.yaci.core.protocol.AgentListener;
import com.bloxbean.cardano.yaci.core.protocol.localtx.messages.MsgAcceptTx;
import com.bloxbean.cardano.yaci.core.protocol.localtx.messages.MsgRejectTx;
import com.bloxbean.cardano.yaci.core.protocol.localtx.model.TxSubmissionRequest;

public interface LocalTxSubmissionListener extends AgentListener {
    default void txAccepted(TxSubmissionRequest txSubmissionRequest, MsgAcceptTx msgAcceptTx) {

    }

    default void txRejected(TxSubmissionRequest txSubmissionRequest, MsgRejectTx msgRejectTx) {

    }
}
