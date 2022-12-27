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
                applyMonoSuccess(new MsgRelease(), null);
            }

            @Override
            public void onDisconnect() {
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
        return Mono.create(monoSink -> {
            if (log.isDebugEnabled())
                log.debug("Release()");
            MsgRelease msgRelease = localStateQueryAgent.release();
            localStateQueryAgent.sendNextMessage();
            monoSink.success(null);
        });
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
        if (localStateQueryAgent.getCurrentState() == LocalStateQueryState.Acquired) {
            log.info("Already in acquired state. Ignoring the acquire call");
            return Mono.just(Optional.empty());
        }

        return Mono.create(monoSink -> {
            if (log.isDebugEnabled())
                log.debug("Try to acquire again");
            MsgAcquire msgAcquire = localStateQueryAgent.acquire(point);
            storeMonoSinkReference(msgAcquire, monoSink);

            localStateQueryAgent.sendNextMessage();
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
