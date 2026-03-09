package com.bloxbean.cardano.yaci.node.api.plugin;

public interface NodePolicy {
    default boolean acceptBlock(Object blockReceivedEvent) { return true; }
    default Object onRollbackTarget(Object targetPoint) { return targetPoint; }
}

