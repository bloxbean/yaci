package com.bloxbean.cardano.yaci.node.api.events;

import com.bloxbean.cardano.yaci.events.api.VetoableEvent;
import com.bloxbean.cardano.yaci.node.api.ledger.AppBlock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Published before an app block is finalized.
 * Listeners can veto to prevent finalization (e.g., consensus threshold not met).
 */
public final class AppBlockConsensusEvent implements VetoableEvent {

    private final AppBlock block;
    private final List<Rejection> rejections = new ArrayList<>();

    public AppBlockConsensusEvent(AppBlock block) {
        this.block = block;
    }

    public AppBlock block() { return block; }

    @Override
    public void reject(String source, String reason) {
        rejections.add(new Rejection(source, reason));
    }

    @Override
    public boolean isRejected() {
        return !rejections.isEmpty();
    }

    @Override
    public List<Rejection> rejections() {
        return Collections.unmodifiableList(rejections);
    }
}
