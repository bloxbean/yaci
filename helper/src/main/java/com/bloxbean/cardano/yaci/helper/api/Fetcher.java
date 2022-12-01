package com.bloxbean.cardano.yaci.helper.api;

import java.util.function.Consumer;

public interface Fetcher<T> {
    default void start() {
        start(null);
    }

    void start(Consumer<T> t);

    void shutdown();

    boolean isRunning();
}
