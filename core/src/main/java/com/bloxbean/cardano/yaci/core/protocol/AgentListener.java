package com.bloxbean.cardano.yaci.core.protocol;

public interface AgentListener {
    default void onStateUpdate(State oldState, State newState) {

    }

    default void onDisconnect() {}
}
