package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.helper.api.Fetcher;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The starts of one {@link BlockSync} or {@link BlockRangeSync} (ADR 0016 D3c). Each start owns a fresh main client,
 * its listener delegate (when inlining is on) and its own endorser block source with its side connection; the
 * announcement cache belongs to the helper.
 * <ul>
 *   <li>One lock serializes start, restart, stop and the halting thread. It only selects, retires and publishes a
 *       start; connecting, shutting down and application code run outside it.</li>
 *   <li>Retiring a start cancels its certifying block's call in progress, closes its side connection and shuts its
 *       client down, with rechecks ({@link ClientRetirement}).</li>
 *   <li>A start's client is started only while the start is current, and shut down again if it was retired while
 *       starting.</li>
 *   <li>The halting thread retires the halted start only while it is still the current one.</li>
 *   <li>Nothing on the main connection's event loop takes the lock.</li>
 * </ul>
 */
@Slf4j
final class SyncControl<C extends Fetcher<Block>> {
    /**
     * One start: its client, its delegate (null when inlining is off), its endorser block source, and its retirement
     * flag, which the delegate reads too.
     */
    static final class Start<C> {
        final C client;
        final CompleteBlockAssembler delegate;
        final CompleteBlockAssembler.EndorserBlockSource source;
        final AtomicBoolean retired;
        /** True while the client's own {@code start()} has not returned: retirement keeps checking until then. */
        volatile boolean starting;

        Start(C client, CompleteBlockAssembler delegate, CompleteBlockAssembler.EndorserBlockSource source,
              AtomicBoolean retired) {
            this.client = client;
            this.delegate = delegate;
            this.source = source;
            this.retired = retired;
        }
    }

    /** A replaced start: the one to retire (may be null) and the one to start. */
    record Swap<C>(Start<C> displaced, Start<C> created) {
    }

    private final Object lock = new Object();
    private final LeiosConfig config;
    private final EbAnnouncementCache cache;
    private final Function<Supplier<C>, CompleteBlockAssembler.EndorserBlockSource> sourceFactory;
    private final long budgetMillis;
    private final long pauseMillis;
    private final Consumer<Runnable> haltRunner;
    private volatile Start<C> current;

    /**
     * @param sourceFactory the endorser block source of a start, given that start's client
     * @param haltRunner    runs the halting task: a new thread ({@link #runOnNewThread}); tests can delay it
     */
    SyncControl(LeiosConfig config, EbAnnouncementCache cache,
                Function<Supplier<C>, CompleteBlockAssembler.EndorserBlockSource> sourceFactory,
                long budgetMillis, long pauseMillis, Consumer<Runnable> haltRunner) {
        this.config = config != null ? config : LeiosConfig.defaultConfig();
        this.cache = cache;
        this.sourceFactory = sourceFactory;
        this.budgetMillis = budgetMillis;
        this.pauseMillis = pauseMillis;
        this.haltRunner = haltRunner;
    }

    /**
     * Retires the current start and publishes a new one: a new delegate and source (when inlining is on) and the
     * client that {@code clientFactory} wires to the delegate. The caller then calls {@link #retire} on
     * {@link Swap#displaced()} and {@link #start} on {@link Swap#created()}, outside the lock.
     */
    Swap<C> replace(BlockChainDataListener listener, Function<BlockChainDataListener, C> clientFactory) {
        synchronized (lock) {
            Start<C> old = current;
            markRetired(old);
            if (old != null && old.delegate != null)
                old.delegate.replaced(); //a reconnect of the old client delivers nothing but disconnects
            CompleteBlockAssembler delegate = null;
            CompleteBlockAssembler.EndorserBlockSource source = null;
            AtomicReference<C> owner = new AtomicReference<>();
            AtomicBoolean retired = new AtomicBoolean();
            if (config.isInlineEndorserBlockTxs() && listener != null) {
                source = sourceFactory.apply(owner::get);
                delegate = new CompleteBlockAssembler(listener, cache, source,
                        config.isSkipUnavailableEndorserBlocks(), retired, this::halted, budgetMillis, pauseMillis);
            }
            C client = clientFactory.apply(delegate != null ? delegate : listener);
            owner.set(client);
            Start<C> created = new Start<>(client, delegate, source, retired);
            current = created;
            return new Swap<>(old, created);
        }
    }

    /**
     * A new start (startSync, startSyncFromTip, start, restart): {@link #replace}, {@link #retire} the displaced
     * start, {@link #start} the new one.
     *
     * @return the new start's client
     */
    C startNew(BlockChainDataListener listener, Function<BlockChainDataListener, C> clientFactory) {
        Swap<C> swap = replace(listener, clientFactory);
        retire(swap.displaced()); //always: a client that is not running may still be starting or reconnecting
        start(swap.created()); //only while current; shut down again if retired while starting
        return swap.created().client;
    }

    /** stop(): retires the current start and its client. */
    void stop() {
        retire(retireCurrent());
    }

    /**
     * Retires the current start (stop) and returns it, for the caller to {@link #retire} outside the lock.
     */
    Start<C> retireCurrent() {
        synchronized (lock) {
            Start<C> old = current;
            markRetired(old);
            return old;
        }
    }

    /**
     * Starts the client of {@code start} if the start is still current, and retires it again if it was retired
     * while starting. Never call it holding the lock.
     */
    void start(Start<C> start) {
        synchronized (lock) { //a retirement after this check sees the start as starting, and keeps checking it
            if (start != current || start.retired.get())
                return;
            start.starting = true;
        }
        try {
            start.client.start();
        } catch (RuntimeException e) {
            if (isCurrent(start))
                throw e;
            //a start superseded while connecting fails as it is shut down: not the caller's failure
            log.debug("A superseded start failed while starting", e);
        } finally {
            start.starting = false;
        }
        if (!isCurrent(start))
            retire(start);
    }

    /**
     * Closes a retired start's side connection and shuts its client down, with rechecks. Never call it holding the
     * lock.
     */
    void retire(Start<C> start) {
        if (start == null)
            return;
        if (start.source != null)
            start.source.close();
        C client = start.client;
        ClientRetirement.retire(client, client::shutdown, client::isRunning, () -> start.starting);
    }

    /** The current start, or null before the first one. */
    Start<C> current() {
        return current;
    }

    /** The current main client, or null before the first start. */
    C client() {
        Start<C> start = current;
        return start != null ? start.client : null;
    }

    /** False once the current start halted, else the client's own state. */
    boolean isRunning() {
        Start<C> start = current;
        return !isHalted(start) && start.client.isRunning();
    }

    /** True if the current start halted. */
    boolean isHalted() {
        return isHalted(current);
    }

    private boolean isCurrent(Start<C> start) {
        synchronized (lock) {
            return start == current && !start.retired.get();
        }
    }

    private static boolean isHalted(Start<?> start) {
        return start != null && start.delegate != null && start.delegate.isHalted();
    }

    private static void markRetired(Start<?> start) {
        if (start == null)
            return;
        start.retired.set(true);
        if (start.delegate != null)
            start.delegate.cancelCall(); //a certifying block's call in progress; does not block
    }

    /**
     * Called on the main connection's thread after the halted delegate reported {@code SYNC_HALTED} and stopped
     * forwarding. The halting task retires the halted start, unless a newer start replaced it.
     */
    private void halted(CompleteBlockAssembler delegate) {
        haltRunner.accept(() -> {
            Start<C> halted;
            synchronized (lock) {
                halted = current;
                if (halted == null || halted.delegate != delegate || halted.retired.get())
                    return;
                halted.retired.set(true);
            }
            retire(halted);
        });
    }

    static void runOnNewThread(Runnable task) {
        Thread thread = new Thread(task, "yaci-leios-halt");
        thread.setDaemon(true);
        thread.start();
    }
}
