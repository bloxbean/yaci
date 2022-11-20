package com.bloxbean.cardano.yaci.core.helpers.api;

import java.util.function.Consumer;

public interface Fetcher<T> {
    default void start() {
        start(null);
    }

    void start(Consumer<T> t);

    void shutdown();

    boolean isRunning();
}
