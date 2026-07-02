package com.bloxbean.cardano.yaci.helper.listener;

import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.Reason;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosPoint;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosRawCbor;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosTxBitmap;

import java.util.List;

public interface LeiosDataListener {
    default void onHandshake(AcceptVersion acceptVersion) {
    }

    default void onLeiosActivated(AcceptVersion acceptVersion) {
    }

    default void onLeiosNotActivated(AcceptVersion acceptVersion) {
    }

    default void onHandshakeError(Reason reason) {
    }

    default void onBlockAnnouncement(LeiosRawCbor announcement) {
    }

    default void onBlockOffer(LeiosPoint point, long ebSize) {
    }

    default void onBlockTxsOffer(LeiosPoint point) {
    }

    default void onVotes(List<LeiosRawCbor> votes) {
    }

    default void onBlock(LeiosPoint requestedPoint, LeiosRawCbor endorserBlock) {
    }

    default void onBlockTxs(LeiosPoint requestedPoint, LeiosPoint responsePoint,
                            LeiosTxBitmap responseBitmap, LeiosRawCbor txList) {
    }

    default void onNotifyError(Throwable error) {
    }

    default void onFetchError(Throwable error) {
    }

    default void onFetchError(LeiosPoint requestedPoint, Throwable error) {
        onFetchError(error);
    }

    default void onDisconnect() {
    }
}
