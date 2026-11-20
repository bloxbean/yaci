package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.localstate.LocalStateQueryAgent;
import com.bloxbean.cardano.yaci.core.protocol.localstate.LocalStateQueryListener;
import com.bloxbean.cardano.yaci.core.protocol.localstate.LocalStateQueryState;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.Query;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.QueryResult;
import com.bloxbean.cardano.yaci.core.protocol.localstate.messages.MsgAcquire;
import com.bloxbean.cardano.yaci.core.protocol.localstate.messages.MsgFailure;
import com.bloxbean.cardano.yaci.core.protocol.localstate.messages.MsgReAcquire;
import com.bloxbean.cardano.yaci.core.protocol.localstate.messages.MsgRelease;
import com.bloxbean.cardano.yaci.helper.api.QueryClient;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Use this to query local ledger state using Node-to-client local-state-query mini-protocol
 *
 * <p>
 * Create a {@link LocalClientProvider} to get an instance of this class.
 * </p>
 *
 * <p>
 * Example: Query system start time
 * </p>
 * <pre>
 * {@code
 *  LocalClientProvider localClientProvider = new LocalClientProvider(nodeSocketFile, protocolMagic);
 *  LocalStateQueryClient localStateQueryClient = localClientProvider.getLocalStateQueryClient();
 *  localClientProvider.start();
 *
 *  Mono<SystemStartResult> queryResultMono = localStateQueryClient.executeQuery(new SystemStartQuery());
 *  SystemStartResult result = queryResultMono.block();
 * }
 * </pre>
 */
@Slf4j
public class LocalStateQueryClient extends QueryClient {
    private LocalStateQueryAgent localStateQueryAgent;
    /** Callers waiting on the one MsgRelease in flight; guarded by itself. */
    private final List<MonoSink<Void>> releaseWaiters = new ArrayList<>();

    /**
     * Construct a LocalStateQueryClient
     *
     * @param localStateQueryAgent
     */
    public LocalStateQueryClient(LocalStateQueryAgent localStateQueryAgent) {
        this.localStateQueryAgent = localStateQueryAgent;
        init();
    }

    private void init() {
        localStateQueryAgent.addListener(new LocalStateQueryListener() {
            @Override
            public void resultReceived(Query query, QueryResult result) {
                applyMonoSuccess(query, result);
            }

            @Override
            public void acquired(Point point) {
                MsgReAcquire key = new MsgReAcquire(point);
                if (hasMonoSink(key))
                    applyMonoSuccess(key, Optional.ofNullable(point));
                else
                    applyMonoSuccess(new MsgAcquire(point), Optional.ofNullable(point));
            }

            @Override
            public void acquireFailed(MsgFailure.Reason reason) {
                if (log.isDebugEnabled())
                    log.error(String.valueOf(reason));
                applyError(reason);
            }

            @Override
            public void released() {
                if (log.isDebugEnabled())
                    log.debug("Released >>>");
                for (MonoSink<Void> waiter : takeReleaseWaiters())
                    waiter.success();
            }

            @Override
            public void onDisconnect() {
                failReleases("Connection Error !!!");
                applyError("Connection Error !!!");
            }
        });
    }

    /**
     * Release the acquired position
     *
     * @return
     */
    public Mono<Void> release() {
        // completes once MsgRelease is sent and the agent is Idle, so a query after it acquires first; calls while a
        // release is in flight wait on that one, as a second MsgRelease would arrive in Idle
        return Mono.create(monoSink -> {
            if (log.isDebugEnabled())
                log.debug("Release()");
            synchronized (releaseWaiters) {
                releaseWaiters.add(monoSink);
                if (releaseWaiters.size() > 1)
                    return;
            }
            // registered first: a disconnect after this check still fails the waiters through onDisconnect
            if (!localStateQueryAgent.isChannelActive()) {
                failReleases("Not connected");
                return;
            }
            try {
                localStateQueryAgent.release();
            } catch (RuntimeException e) {
                failReleases(e.getMessage());
                return;
            }
            localStateQueryAgent.sendNextMessage();
        });
    }

    private List<MonoSink<Void>> takeReleaseWaiters() {
        synchronized (releaseWaiters) {
            List<MonoSink<Void>> waiters = new ArrayList<>(releaseWaiters);
            releaseWaiters.clear();
            return waiters;
        }
    }

    private void failReleases(String reason) {
        for (MonoSink<Void> waiter : takeReleaseWaiters())
            waiter.error(new RuntimeException(reason));
    }

    /**
     * Acquire at tip of the chain
     *
     * @return
     */
    public Mono<Optional<Point>> acquire() {
        return acquire(null);
    }

    /**
     * Acquire the given position in the chain
     *
     * @param point
     * @return Mono with acquired point
     */
    public Mono<Optional<Point>> acquire(Point point) {
        // decided at subscription, as in executeQuery: release().then(acquire()) acquires again
        return Mono.defer(() -> {
            if (localStateQueryAgent.getCurrentState() == LocalStateQueryState.Acquired) {
                log.info("Already in acquired state. Ignoring the acquire call");
                return Mono.just(Optional.<Point>empty());
            }
            return Mono.create(monoSink -> {
                if (log.isDebugEnabled())
                    log.debug("Try to acquire again");
                MsgAcquire msgAcquire = localStateQueryAgent.acquire(point);
                storeMonoSinkReference(msgAcquire, monoSink);

                localStateQueryAgent.sendNextMessage();
            });
        });
    }

    /**
     * Reacquire at tip of the chain
     *
     * @return Mono with acquired point
     */
    public Mono<Optional<Point>> reAcquire() {
        return reAcquire(null);
    }

    /**
     * Reacquire at the given point
     *
     * @param point
     * @return
     */
    public Mono<Optional<Point>> reAcquire(Point point) {
        return Mono.create(monoSink -> {
            if (log.isDebugEnabled())
                log.debug("Try to reAcquire at point : {}", point);
            MsgReAcquire msgReAcquire = localStateQueryAgent.reAcquire(point);
            storeMonoSinkReference(msgReAcquire, monoSink);

            localStateQueryAgent.sendNextMessage();
        });
    }

    /**
     * Execute a query
     *
     * @param query Pass a query object
     * @param <T>
     * @return Mono with instance of {@link QueryResult}
     */
    public <T extends QueryResult> Mono<T> executeQuery(Query query) {
        // decided at subscription, not when the chain is built: a release().then(executeQuery(..)) runs in Idle
        return Mono.defer(() -> dispatch(query));
    }

    private <T extends QueryResult> Mono<T> dispatch(Query query) {
        if (localStateQueryAgent.getCurrentState() == LocalStateQueryState.Idle) {
            //Auto acquire
            return Mono.create(monoSink -> {
                acquire()
                        .doOnNext(point -> {
                            localStateQueryAgent.query(query);
                            storeMonoSinkReference(query, monoSink);
                            localStateQueryAgent.sendNextMessage();
                        })
                        .doOnError(throwable -> monoSink.error(throwable))
                        .subscribe();
            });
        } else {
            return Mono.create(monoSink -> {
                localStateQueryAgent.query(query);
                storeMonoSinkReference(query, monoSink);
                localStateQueryAgent.sendNextMessage();
            });
        }
    }
}
