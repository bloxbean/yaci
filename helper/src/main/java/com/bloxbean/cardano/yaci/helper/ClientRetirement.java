package com.bloxbean.cardano.yaci.helper;

import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Shuts down a client that belongs to a retired start or a closed side connection (ADR 0016 D3c, D4a), and shuts it
 * down again 5 s and 30 s later if it runs by then: an automatic reconnect that was between sessions, or a
 * {@code start()} that ran after the retirement, would otherwise revive it. While the client's own {@code start()}
 * has not returned, it keeps checking every 30 s. Whatever the client delivers in between goes to a retired delegate,
 * which drops it.
 * <p>
 * No lock is held across a shutdown. Shutdowns of one client never overlap: a caller that finds one in progress
 * returns at once, and the rechecks back it up. A failed shutdown is logged, never thrown.
 */
@Slf4j
final class ClientRetirement {
    static final long[] RECHECK_MILLIS = {5_000, 30_000};
    static final long WHILE_STARTING_MILLIS = 30_000;
    private static final Set<Object> SHUTTING_DOWN = Collections.newSetFromMap(new ConcurrentHashMap<>());
    /** Timing only: it never runs a shutdown itself. */
    private static final ScheduledExecutorService RECHECKS = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "yaci-leios-retire");
        thread.setDaemon(true);
        return thread;
    });
    /** Runs the shutdowns, which may block: one stuck shutdown never delays another client's. */
    private static final ExecutorService SHUTDOWNS = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "yaci-leios-close");
        thread.setDaemon(true);
        return thread;
    });

    private ClientRetirement() {
    }

    /**
     * Shuts the client down now, on this thread, then schedules the rechecks.
     *
     * @param starting true while the client's own {@code start()} call has not returned
     */
    static void retire(Object client, Runnable shutdown, BooleanSupplier isRunning, BooleanSupplier starting) {
        retire(client, shutdown, isRunning, starting, RECHECK_MILLIS, WHILE_STARTING_MILLIS);
    }

    /** As {@link #retire(Object, Runnable, BooleanSupplier, BooleanSupplier)}, with the delays given (tests). */
    static void retire(Object client, Runnable shutdown, BooleanSupplier isRunning, BooleanSupplier starting,
                       long[] recheckMillis, long whileStartingMillis) {
        shutdown(client, shutdown);
        for (int i = 0; i < recheckMillis.length; i++) {
            boolean last = i == recheckMillis.length - 1;
            RECHECKS.schedule(() -> {
                SHUTDOWNS.execute(() -> recheck(client, shutdown, isRunning));
                if (last)
                    whileStarting(client, shutdown, isRunning, starting, whileStartingMillis);
            }, recheckMillis[i], TimeUnit.MILLISECONDS);
        }
    }

    /** As {@link #retire(Object, Runnable, BooleanSupplier, BooleanSupplier)}, on another thread. */
    static void retireAsync(Object client, Runnable shutdown, BooleanSupplier isRunning, BooleanSupplier starting) {
        SHUTDOWNS.execute(() -> retire(client, shutdown, isRunning, starting));
    }

    private static void whileStarting(Object client, Runnable shutdown, BooleanSupplier isRunning,
                                      BooleanSupplier starting, long millis) {
        if (!starting.getAsBoolean())
            return; //its start returned: the starter's own check after start() takes over
        RECHECKS.schedule(() -> {
            SHUTDOWNS.execute(() -> recheck(client, shutdown, isRunning));
            whileStarting(client, shutdown, isRunning, starting, millis);
        }, millis, TimeUnit.MILLISECONDS);
    }

    private static void recheck(Object client, Runnable shutdown, BooleanSupplier isRunning) {
        if (isRunning.getAsBoolean())
            shutdown(client, shutdown);
    }

    /** Shuts the client down once, now, with no rechecks: a start that completed after its close. */
    static void shutdownNow(Object client, Runnable shutdown) {
        shutdown(client, shutdown);
    }

    private static void shutdown(Object client, Runnable shutdown) {
        if (!SHUTTING_DOWN.add(client))
            return; //another caller is shutting it down
        try {
            shutdown.run();
        } catch (RuntimeException e) {
            log.warn("Shutting down a retired client failed", e);
        } finally {
            SHUTTING_DOWN.remove(client);
        }
    }
}
