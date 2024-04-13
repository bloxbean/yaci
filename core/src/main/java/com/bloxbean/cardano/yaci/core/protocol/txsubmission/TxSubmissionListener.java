package com.bloxbean.cardano.yaci.core.protocol.txsubmission;

import com.bloxbean.cardano.yaci.core.protocol.AgentListener;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges.RequestTxIds;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.messges.RequestTxs;

public interface TxSubmissionListener extends AgentListener {
     void handleRequestTxs(RequestTxs requestTxs);

     void handleRequestTxIdsNonBlocking(RequestTxIds requestTxIds);

     void handleRequestTxIdsBlocking(RequestTxIds requestTxIds);
}
