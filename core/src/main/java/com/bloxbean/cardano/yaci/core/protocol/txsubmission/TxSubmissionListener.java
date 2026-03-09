package com.bloxbean.cardano.yaci.core.protocol.txsubmission;

import com.bloxbean.cardano.yaci.core.protocol.AgentListener;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges.ReplyTxIds;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges.ReplyTxs;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges.RequestTxIds;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges.RequestTxs;

public interface TxSubmissionListener extends AgentListener {
    // Client-side methods (for handling requests from server)
    void handleRequestTxs(RequestTxs requestTxs);
    void handleRequestTxIdsNonBlocking(RequestTxIds requestTxIds);
    void handleRequestTxIdsBlocking(RequestTxIds requestTxIds);
    
    // Server-side methods (for handling replies from client)
    default void handleReplyTxIds(ReplyTxIds replyTxIds) {
        // Default empty implementation for backward compatibility
    }
    
    default void handleReplyTxs(ReplyTxs replyTxs) {
        // Default empty implementation for backward compatibility
    }
}
