package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.localstate.LocalStateQueryAgent;
import com.bloxbean.cardano.yaci.core.protocol.localstate.LocalStateQueryListener;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.Query;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.QueryResult;
import com.bloxbean.cardano.yaci.core.protocol.localstate.messages.MsgAcquire;
import com.bloxbean.cardano.yaci.core.protocol.localstate.messages.MsgFailure;
import com.bloxbean.cardano.yaci.core.protocol.localstate.messages.MsgReAcquire;
import com.bloxbean.cardano.yaci.core.protocol.localstate.messages.MsgRelease;
import com.bloxbean.cardano.yaci.core.protocol.localstate.queries.ChainPointQuery;
import com.bloxbean.cardano.yaci.core.protocol.localstate.queries.ChainPointQueryResult;
import com.bloxbean.cardano.yaci.helper.api.QueryClient;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Use this to query local ledger state using Node-to-client local-state-query mini-protocol
 *
 *<p>
 *Create a {@link LocalClientProvider} to get an instance of this class.
 *</p>
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
                applyMonoSuccess(new MsgReAcquire(point), point);
            }

            @Override
            public void acquireFailed(MsgFailure.Reason reason) {

            }

            @Override
            public void released() {
                if (log.isDebugEnabled())
                    log.debug("Released >>>");
                applyMonoSuccess(new MsgRelease(), null);
            }
        });
    }

    /**
     * Release the acquired position
     * @return
     */
    public Mono<Void> release() {
        return Mono.create(monoSink -> {
            MsgRelease msgRelease = localStateQueryAgent.release();
            localStateQueryAgent.sendNextMessage();
            monoSink.success(null);
        });
    }

    /**
     * Acquire the given position in the chain
     * @param point
     * @return Mono with acquired point
     */
    public Mono<Point> acquire(Point point) {
        return Mono.create(monoSink -> {
            if (log.isDebugEnabled())
                log.debug("Try to acquire again");
            MsgAcquire msgAcquire = localStateQueryAgent.acquire(point);
            storeMonoSinkReference(msgAcquire, monoSink);

            localStateQueryAgent.sendNextMessage();
        });
    }

    /**
     * Reacquire at chain point
     * @return Mono with acquired point
     */
    public Mono<Point> reAcquire() {
        return Mono.create(monoSink -> {
            Mono<ChainPointQueryResult> chainPointQueryResultMono = executeQuery(new ChainPointQuery());
            ChainPointQueryResult result = chainPointQueryResultMono.block();

            if (log.isDebugEnabled())
                log.debug("Try to reAcquire at point : {}", result.getChainPoint());
            MsgReAcquire msgReAcquire = localStateQueryAgent.reAcquire(result.getChainPoint());
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
        return Mono.create(monoSink -> {
            localStateQueryAgent.query(query);
            storeMonoSinkReference(query, monoSink);
            localStateQueryAgent.sendNextMessage();
        });
    }
}
