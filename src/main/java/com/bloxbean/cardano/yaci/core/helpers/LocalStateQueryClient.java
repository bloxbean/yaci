package com.bloxbean.cardano.yaci.core.helpers;

import com.bloxbean.cardano.yaci.core.helpers.api.ReactiveFetcher;
import com.bloxbean.cardano.yaci.core.network.N2CClient;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2CVersionTableConstant;
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
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Use this to query local ledger state using Node-to-client local-state-query mini-protocol
 *
 * <p>
 * Example: Query system start time
 * </p>
 * <pre>
 * {@code
 * LocalStateQueryClient queryClient = new LocalStateQueryClient(nodeSocketFile, Constants.PREVIEW_PROTOCOL_MAGIC);
 * queryClient.start();
 *
 * Mono<SystemStartResult> queryResultMono = queryClient.executeQuery(new SystemStartQuery());
 * SystemStartResult result = queryResultMono.block();
 * }
 * </pre>
 */
@Slf4j
public class LocalStateQueryClient extends ReactiveFetcher<QueryResult> {
    private String nodeSocketFile;
    private VersionTable versionTable;
    private HandshakeAgent handshakeAgent;
    private LocalStateQueryAgent localStateQueryAgent;
    private N2CClient n2cClient;

    private Map<Object, MonoSink> monoSinkMap = new ConcurrentHashMap<>();

    /**
     * Construct a LocalStateQueryClient
     * @param nodeSocketFile Cardano node socket file
     * @param protocolMagic Protocol Magic
     */
    public LocalStateQueryClient(String nodeSocketFile, long protocolMagic) {
        this(nodeSocketFile, N2CVersionTableConstant.v1AndAbove(protocolMagic));
    }

    /**
     * Constuct a LocalStateQueryClient
     * @param nodeSocketFile Cardano node socket file
     * @param versionTable VersionTable for Node-to-Client protocol
     */
    public LocalStateQueryClient(String nodeSocketFile, VersionTable versionTable) {
        this.nodeSocketFile = nodeSocketFile;
        this.versionTable = versionTable;
        init();
    }

    private void init() {
        handshakeAgent = new HandshakeAgent(versionTable);
        localStateQueryAgent = new LocalStateQueryAgent();
        n2cClient = new N2CClient(nodeSocketFile, handshakeAgent, localStateQueryAgent);

        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                localStateQueryAgent.sendNextMessage();
            }
        });
    }

    /**
     * Establish the connection with the local Cardano node
     * This method should be called first
     */
    public void start() {
        start(null);
    }

    @Override
    public void start(Consumer<QueryResult> consumer) {
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

        n2cClient.start();

    }

    public Mono<Void> release() {
        return Mono.create(monoSink -> {
            MsgRelease msgRelease = localStateQueryAgent.release();
            localStateQueryAgent.sendNextMessage();
            monoSink.success(null);
        });
    }

    public Mono<Point> acquire(Point point) {
        return Mono.create(monoSink -> {
            if (log.isDebugEnabled())
                log.debug("Try to acquire again");
            MsgAcquire msgAcquire = localStateQueryAgent.acquire(point);
            storeMonoSinkReference(msgAcquire, monoSink);

            localStateQueryAgent.sendNextMessage();
        });
    }

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
     * @param query Pass a query object
     * @return Mono with instance of {@link QueryResult}
     * @param <T>
     */
    public <T extends QueryResult> Mono<T> executeQuery(Query query) {
        return Mono.create(monoSink -> {
            localStateQueryAgent.query(query);
            storeMonoSinkReference(query, monoSink);
            localStateQueryAgent.sendNextMessage();
        });
    }

    /**
     * Invoke this method to shutdown the connection
     */
    @Override
    public void shutdown() {
        n2cClient.shutdown();
    }

    /**
     * Check if the connection is alive
     * @return
     */
    @Override
    public boolean isRunning() {
        return n2cClient.isRunning();
    }

    /**
     * Add a {@link LocalStateQueryListener} to listen query events published by {@link LocalStateQueryAgent}
     * @param listener
     */
    public void addLocalStateQueryListener(LocalStateQueryListener listener) {
        if (this.isRunning())
            throw new IllegalStateException("Listener can be added only before start() call");

        if (listener != null)
            localStateQueryAgent.addListener(listener);
    }

}
