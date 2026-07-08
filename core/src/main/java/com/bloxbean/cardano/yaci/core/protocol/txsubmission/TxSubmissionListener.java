package com.bloxbean.cardano.yaci.core.protocol.txsubmission;

import com.bloxbean.cardano.yaci.core.protocol.AgentListener;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges.ReplyTxIds;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges.ReplyTxs;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges.RequestTxIds;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges.RequestTxs;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges.TxId;

public interface TxSubmissionListener extends AgentListener {
    // Client-side methods (for handling requests from server)
    void handleRequestTxs(RequestTxs requestTxs);
    void handleRequestTxIdsNonBlocking(RequestTxIds requestTxIds);
    void handleRequestTxIdsBlocking(RequestTxIds requestTxIds);
    
    // Server-side methods (for handling replies from client)
    default void handleReplyTxIds(ReplyTxIds replyTxIds) {
        // Default empty implementation for backward compatibility
    }

    default boolean shouldRequestTx(TxId txId, int size) {
        return true;
    }
    
    default void handleReplyTxs(ReplyTxs replyTxs) {
        // Default empty implementation for backward compatibility
    }
}
