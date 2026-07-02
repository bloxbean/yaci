package com.bloxbean.cardano.yaci.core.protocol.leiosnotify;

import com.bloxbean.cardano.yaci.core.protocol.AgentListener;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosPoint;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosRawCbor;

import java.util.List;

public interface LeiosNotifyAgentListener extends AgentListener {
    default void onBlockAnnouncement(LeiosRawCbor announcement) {
    }

    default void onBlockOffer(LeiosPoint point, long ebSize) {
    }

    default void onBlockTxsOffer(LeiosPoint point) {
    }

    default void onVotes(List<LeiosRawCbor> votes) {
    }

    default void onNotifyError(Throwable error) {
    }
}
