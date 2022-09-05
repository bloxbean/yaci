package com.bloxbean.cardano.yaci.core.protocol.localstate;

import com.bloxbean.cardano.yaci.core.protocol.AgentListener;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.Query;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.QueryResult;
import com.bloxbean.cardano.yaci.core.protocol.localstate.messages.MsgFailure;

public interface LocalStateQueryListener extends AgentListener {

    default void acquired(Point point) {

    }

    default void acquireFailed(MsgFailure.Reason reason) {

    }

    default void resultReceived(Query query, QueryResult result) {

    }

    default void released() {

    }
}
