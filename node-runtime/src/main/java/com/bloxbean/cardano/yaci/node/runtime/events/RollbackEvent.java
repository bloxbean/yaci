package com.bloxbean.cardano.yaci.node.runtime.events;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.events.api.Event;

public final class RollbackEvent implements Event {
    private final Point target;
    private final boolean realReorg;

    public RollbackEvent(Point target, boolean realReorg) {
        this.target = target;
        this.realReorg = realReorg;
    }

    public Point target() { return target; }
    public boolean realReorg() { return realReorg; }
}

