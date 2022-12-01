package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.protocol.localtxmonitor.LocalTxMonitorAgent;
import com.bloxbean.cardano.yaci.core.protocol.localtxmonitor.LocalTxMonitorListener;
import com.bloxbean.cardano.yaci.core.protocol.localtxmonitor.messages.*;
import com.bloxbean.cardano.yaci.helper.api.QueryClient;
import com.bloxbean.cardano.yaci.helper.model.MempoolStatus;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Helper to query mempool of a local Cardano node using local tx monitor node-to-client mini protocol.
 * <p>
 * Create a {@link LocalClientProvider} to get an instance of this class.
 * </p>
 * Example:
 *
 * <pre>{@code
 *     LocalClientProvider localClientProvider = new LocalClientProvider(nodeSocketFile, protocolMagic);
 *     LocalTxMonitorClient localTxMonitorClient = localClientProvider.getTxMonitorClient();
 *     localClientProvider.start();
 * }</pre>
 */
@Slf4j
public class LocalTxMonitorClient extends QueryClient {
    private LocalTxMonitorAgent localTxMonitorAgent;

    public LocalTxMonitorClient(LocalTxMonitorAgent localTxMonitorAgent) {
        this.localTxMonitorAgent = localTxMonitorAgent;
        init();
    }

    private void init() {
        localTxMonitorAgent.addListener(new LocalTxMonitorListener() {
            @Override
            public void acquiredAt(MsgAwaitAcquire request, MsgAcquired msgAcquired) {
                if (log.isDebugEnabled())
                    log.debug("TxMonitor acquired at : " + msgAcquired.getSlotNo());
                applyMonoSuccess(request, msgAcquired.getSlotNo());
            }

            @Override
            public void onReplyHashTx(MsgHasTx request, MsgReplyHasTx reply) {
                applyMonoSuccess(request, reply);
            }

            @Override
            public void onReplyNextTx(MsgNextTx request, MsgReplyNextTx reply) {
                byte[] transaction = reply.getTransaction();
                if (transaction == null)
                    transaction = new byte[0];
                applyMonoSuccess(request, transaction);
            }

            @Override
            public void onReplyGetSizes(MsgGetSizes request, MsgReplyGetSizes reply) {
                MempoolStatus mempoolStatus = MempoolStatus.builder()
                        .capacityInBytes(reply.getCapacityInBytes())
                        .sizeInBytes(reply.getSizeInBytes())
                        .numberOfTxs(reply.getNumberOfTxs())
                        .build();
                applyMonoSuccess(request, mempoolStatus);
            }
        });
    }

    /**
     * Acquire a mempool snapshot
     * The local-tx-monitor is a stateful protocol with explicit state acquisition driven by the client. That is, clients
     * must first acquire a mempool snapshot for running queries over it.
     * @return Mono for slot number
     */
    public Mono<Long> acquire() {
        return Mono.create(monoSink -> {
            if (log.isDebugEnabled())
                log.debug("Try to acquire for LocalTxMonitor");
            MsgAwaitAcquire msgAwaitAcquire = localTxMonitorAgent.awaitAcquire();
            storeMonoSinkReference(msgAwaitAcquire, monoSink);

            localTxMonitorAgent.sendNextMessage();
        });
    }

    /**
     * Get size and capacity of the mempool for the currently acquired snapshot.
     * <p>This method should be called after {@link #acquire()}</p>
     * @return Mono for {@link MempoolStatus}
     */
    public Mono<MempoolStatus> getMempoolSizeAndCapacity() {
        return Mono.create(monoSink -> {
            MsgGetSizes sizeRequest = localTxMonitorAgent.getSizeAndCapacity();
            storeMonoSinkReference(sizeRequest, monoSink);
            localTxMonitorAgent.sendNextMessage();
        });
    }

    /**
     * First acquire a mempool snapshot and then query mempool size and capacity.
     * This method automatically handles the acquire call before query.
     * @return Mono for {@link MempoolStatus}
     */
    public Mono<MempoolStatus> acquireAndGetMempoolSizeAndCapacity() {
        return Mono.create(monoSink -> {
            acquire().subscribe(aLong -> {
                if (log.isDebugEnabled())
                    log.debug("Try to acquire for LocalTxMonitor");

                MsgGetSizes sizeRequest = localTxMonitorAgent.getSizeAndCapacity();
                storeMonoSinkReference(sizeRequest, monoSink);
                localTxMonitorAgent.sendNextMessage();

            });
        });
    }

    /**
     * Get transactions in the current snapshot of mempool as Mono
     * <p>This method should be called after {@link #acquire()}</p>
     * @return Mono for List of transactions
     */
    public Mono<List<byte[]>> getCurrentMempoolTransactions() {
        return getCurrentMempoolTransactionsAsFlux().collectList();
    }

    /**
     * Get transactions in the current snapshot of mempool
     * <p>This method should be called after {@link #acquire()}</p>
     * @return Flux for transactions (bytes)
     */
    public Flux<byte[]> getCurrentMempoolTransactionsAsFlux() {
        return Flux.create(fluxSink -> {
            _getMempoolTransactions(fluxSink, false);
        });
    }

    /**
     * First acquire a mempool snapshot and then query mempool for transactions
     * This method automatically handles the acquire call before the query.
     * @return Flux for transactions
     */
    public Flux<byte[]> acquireAndGetMempoolTransactions() {
        return Flux.create(fluxSink -> {
            acquire().subscribe(slot -> {
                _getMempoolTransactions(fluxSink, false);
            });
        });
    }


    /**
     * Stream available transactions from the mempool by automatically acquiring mempool snapshots.
     * It continuously acquires the mempool snapshots and streams all available transactions in that snapshot.
     * @return Flux for transactions (bytes)
     */
    public Flux<byte[]> streamMempoolTransactions() {
        return Flux.create(fluxSink -> {
            acquire().subscribe(slot -> {
                _getMempoolTransactions(fluxSink, true);
            });
        });
    }

    private void _getMempoolTransactions(FluxSink fluxSink, boolean stream) {
        getNextTx().subscribe(bytes -> {
            if (bytes == null || bytes.length == 0) {
                if (!stream) {
                    if (log.isDebugEnabled())
                        log.debug("FluxSink.complete()");
                    fluxSink.complete();
                } else {
                    if (log.isDebugEnabled())
                        log.debug("Streaming mode. Re-acquire and wait for next set of transactions");
                    acquire().subscribe(slot -> _getMempoolTransactions(fluxSink, stream));
                }

            } else {
                fluxSink.next(bytes);
                _getMempoolTransactions(fluxSink, stream);
            }
        });
    }

    /**
     * Get next transaction in the acquired snapshot.
     * This method should be called after {@link #acquire()} method.
     * @return Next transaction, null if there is no available transaction in the mempool snapshot
     */
    public Mono<byte[]> getNextTx() {
        return Mono.create(monoSink -> {
            MsgNextTx nextTxRequest = localTxMonitorAgent.nextTx();
            storeMonoSinkReference(nextTxRequest, monoSink);
            localTxMonitorAgent.sendNextMessage();
        });
    }
}
