package com.bloxbean.cardano.yaci.helper;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.leios.EndorserBlock;
import com.bloxbean.cardano.yaci.core.model.leios.EndorserBlockTx;
import com.bloxbean.cardano.yaci.core.model.leios.LeiosVote;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockHeaderSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.leios.EndorserBlockSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.leios.EndorserBlockTxListSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.leios.LeiosVoteSerializer;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosPoint;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosRawCbor;
import com.bloxbean.cardano.yaci.core.protocol.leios.LeiosTxBitmap;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.LeiosFetchAgent;
import com.bloxbean.cardano.yaci.core.protocol.leiosfetch.LeiosFetchAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.leiosnotify.LeiosNotifyAgentListener;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import com.bloxbean.cardano.yaci.helper.model.leios.EndorserBlockEvent;
import com.bloxbean.cardano.yaci.helper.model.leios.LeiosVotesEvent;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Bridges Leios notify/fetch events into BlockChainDataListener callbacks without blocking ChainSync or BlockFetch.
 */
@Slf4j
class LeiosSyncCoordinator implements LeiosNotifyAgentListener, LeiosFetchAgentListener, AutoCloseable {
    private static final int MAX_PENDING_ENDORSER_BLOCKS = 1_000;
    private static final int MAX_RECENT_ANNOUNCEMENTS = 128;

    private final BlockChainDataListener blockChainDataListener;
    private final LeiosFetchAgent leiosFetchAgent;
    private final LeiosConfig leiosConfig;
    private final boolean voteListenerActive;
    private final ScheduledExecutorService scheduler;
    private final Map<String, PendingEndorserBlock> pendingByEbHash =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, PendingEndorserBlock> eldest) {
                    boolean remove = size() > MAX_PENDING_ENDORSER_BLOCKS;
                    if (remove) {
                        eldest.getValue().cancelTimeout();
                    }
                    return remove;
                }
            };
    private final Map<String, String> announcementCborByEbHash =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_RECENT_ANNOUNCEMENTS;
                }
            };

    LeiosSyncCoordinator(BlockChainDataListener blockChainDataListener,
                         LeiosFetchAgent leiosFetchAgent,
                         LeiosConfig leiosConfig) {
        this.blockChainDataListener = blockChainDataListener;
        this.leiosFetchAgent = leiosFetchAgent;
        this.leiosConfig = leiosConfig != null ? leiosConfig : LeiosConfig.defaultConfig();
        this.voteListenerActive = isListenerMethodOverridden(blockChainDataListener, "onLeiosVotes",
                LeiosVotesEvent.class);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory());
    }

    @Override
    public void onBlockAnnouncement(LeiosRawCbor announcement) {
        try {
            Optional<String> ebHash = announcementEbHash(announcement.getCbor());
            ebHash.ifPresent(hash -> {
                String cbor = announcement.toHex();
                synchronized (this) {
                    announcementCborByEbHash.put(hash, cbor);
                    PendingEndorserBlock pending = pendingByEbHash.get(hash);
                    if (pending != null && pending.announcementCbor == null) {
                        pending.announcementCbor = cbor;
                    }
                }
            });
        } catch (Exception e) {
            log.debug("Unable to correlate Leios block announcement", e);
        }
    }

    @Override
    public void onBlockOffer(LeiosPoint point, long ebSize) {
        boolean requestBlock = false;
        synchronized (this) {
            PendingEndorserBlock pending = pendingFor(point);
            pending.point = point;
            pending.announcedEbSize = ebSize;
            if (pending.announcementCbor == null) {
                pending.announcementCbor = announcementCborByEbHash.get(ebHash(point));
            }
            if (!pending.blockRequested && !pending.emitted) {
                pending.blockRequested = true;
                requestBlock = true;
            }
        }

        if (requestBlock) {
            try {
                leiosFetchAgent.requestBlock(point);
            } catch (Exception e) {
                log.debug("Leios EB fetch request failed for {}", point, e);
                resetFetchRequests(point);
            }
        }
    }

    @Override
    public void onBlockTxsOffer(LeiosPoint point) {
        synchronized (this) {
            PendingEndorserBlock pending = pendingFor(point);
            pending.point = point;
            pending.txsOffered = true;
        }
        requestTxsOrEmit(point);
    }

    @Override
    public void onVotes(List<LeiosRawCbor> rawVotes) {
        if (!leiosConfig.isDeliverVotes() || !voteListenerActive || rawVotes == null || rawVotes.isEmpty()) {
            return;
        }

        List<LeiosVote> votes = new ArrayList<>(rawVotes.size());
        for (LeiosRawCbor rawVote : rawVotes) {
            votes.add(LeiosVoteSerializer.INSTANCE.deserialize(rawVote.getCbor()));
        }
        dispatchVotes(votes);
    }

    @Override
    public void onBlock(LeiosPoint requestedPoint, LeiosRawCbor endorserBlockCbor) {
        EndorserBlock endorserBlock;
        try {
            endorserBlock = EndorserBlockSerializer.INSTANCE.deserialize(endorserBlockCbor.getCbor());
        } catch (Exception e) {
            log.debug("Unable to decode Leios Endorser Block at {}", requestedPoint, e);
            return;
        }

        synchronized (this) {
            PendingEndorserBlock pending = pendingFor(requestedPoint);
            if (pending.emitted) {
                return;
            }
            pending.point = requestedPoint;
            pending.endorserBlock = endorserBlock;
            if (pending.announcementCbor == null) {
                pending.announcementCbor = announcementCborByEbHash.get(ebHash(requestedPoint));
            }
        }
        requestTxsOrEmit(requestedPoint);
    }

    @Override
    public void onBlockTxs(LeiosPoint requestedPoint, LeiosPoint responsePoint,
                           LeiosTxBitmap responseBitmap, LeiosRawCbor txList) {
        List<EndorserBlockTx> transactions;
        try {
            transactions = EndorserBlockTxListSerializer.INSTANCE.deserialize(txList.getCbor());
        } catch (Exception e) {
            log.debug("Unable to decode Leios tx list at {}", requestedPoint, e);
            emitIfEndorserBlockFetched(requestedPoint, false);
            return;
        }

        EndorserBlockEvent event;
        synchronized (this) {
            PendingEndorserBlock pending = pendingByEbHash.get(ebHash(requestedPoint));
            if (pending == null || pending.emitted || pending.endorserBlock == null) {
                return;
            }

            pending.transactions = transactions;
            boolean complete = pending.endorserBlock != null
                    && transactions.size() == pending.endorserBlock.txCount()
                    && pending.requestedTxCount >= pending.endorserBlock.txCount();
            event = buildEvent(pending, complete);
            markEmitted(pending);
        }
        dispatchEndorserBlock(event);
    }

    @Override
    public void onFetchError(LeiosPoint requestedPoint, Throwable error) {
        log.debug("Leios fetch failed for {}", requestedPoint, error);
        resetFetchRequests(requestedPoint);
        emitIfEndorserBlockFetched(requestedPoint, false);
    }

    @Override
    public void onFetchError(Throwable error) {
        log.debug("Leios fetch failed", error);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }

    private void requestTxsOrEmit(LeiosPoint point) {
        TxFetchRequest txFetchRequest = null;
        EndorserBlockEvent event = null;
        synchronized (this) {
            PendingEndorserBlock pending = pendingFor(point);
            if (pending.emitted || pending.endorserBlock == null) {
                return;
            }

            int txCount = pending.endorserBlock.txCount();
            int txsToFetch = Math.min(txCount, leiosConfig.getMaxTxsPerEndorserBlock());
            if (!leiosConfig.isFetchTxs() || txCount == 0 || txsToFetch <= 0) {
                event = buildEvent(pending, false);
                markEmitted(pending);
            } else if (pending.txsOffered && !pending.txFetchRequested) {
                pending.txFetchRequested = true;
                pending.requestedTxCount = txsToFetch;
                txFetchRequest = new TxFetchRequest(point, LeiosTxBitmap.firstN(txsToFetch));
            } else if (!pending.txsOffered && pending.timeout == null) {
                scheduleRefsOnlyTimeout(ebHash(point), pending);
            }
        }

        if (txFetchRequest != null) {
            try {
                leiosFetchAgent.requestBlockTxs(txFetchRequest.point(), txFetchRequest.bitmap());
            } catch (Exception e) {
                log.debug("Leios EB tx fetch request failed for {}", point, e);
                resetTxFetchRequest(point);
                emitIfEndorserBlockFetched(point, false);
            }
        }
        if (event != null) {
            dispatchEndorserBlock(event);
        }
    }

    private void scheduleRefsOnlyTimeout(String key, PendingEndorserBlock pending) {
        long waitMillis = Math.max(0, leiosConfig.getTxsOfferWaitMillis());
        pending.timeout = scheduler.schedule(() -> emitRefsOnlyOnTimeout(key),
                waitMillis, TimeUnit.MILLISECONDS);
    }

    private void emitRefsOnlyOnTimeout(String key) {
        EndorserBlockEvent event = null;
        synchronized (this) {
            PendingEndorserBlock pending = pendingByEbHash.get(key);
            if (pending != null && pending.endorserBlock != null && !pending.emitted
                    && !pending.txFetchRequested) {
                event = buildEvent(pending, false);
                markEmitted(pending);
            }
        }
        if (event != null) {
            dispatchEndorserBlock(event);
        }
    }

    private void emitIfEndorserBlockFetched(LeiosPoint point, boolean txsComplete) {
        EndorserBlockEvent event = null;
        synchronized (this) {
            PendingEndorserBlock pending = pendingByEbHash.get(ebHash(point));
            if (pending != null && pending.endorserBlock != null && !pending.emitted) {
                event = buildEvent(pending, txsComplete);
                markEmitted(pending);
            }
        }
        if (event != null) {
            dispatchEndorserBlock(event);
        }
    }

    private void dispatchEndorserBlock(EndorserBlockEvent event) {
        try {
            blockChainDataListener.onEndorserBlock(event);
        } catch (Exception e) {
            log.warn("BlockChainDataListener.onEndorserBlock failed", e);
        }
    }

    private void dispatchVotes(List<LeiosVote> votes) {
        try {
            blockChainDataListener.onLeiosVotes(LeiosVotesEvent.builder().votes(votes).build());
        } catch (Exception e) {
            log.warn("BlockChainDataListener.onLeiosVotes failed", e);
        }
    }

    private EndorserBlockEvent buildEvent(PendingEndorserBlock pending, boolean txsComplete) {
        return EndorserBlockEvent.builder()
                .point(pending.point)
                .announcedEbSize(pending.announcedEbSize)
                .endorserBlock(pending.endorserBlock)
                .transactions(pending.transactions != null ? pending.transactions : Collections.emptyList())
                .txsComplete(txsComplete)
                .announcementCbor(pending.announcementCbor)
                .build();
    }

    private void resetFetchRequests(LeiosPoint point) {
        synchronized (this) {
            PendingEndorserBlock pending = pendingByEbHash.get(ebHash(point));
            if (pending != null && !pending.emitted) {
                pending.blockRequested = false;
                pending.txFetchRequested = false;
                pending.requestedTxCount = 0;
            }
        }
    }

    private void resetTxFetchRequest(LeiosPoint point) {
        synchronized (this) {
            PendingEndorserBlock pending = pendingByEbHash.get(ebHash(point));
            if (pending != null && !pending.emitted) {
                pending.txFetchRequested = false;
                pending.requestedTxCount = 0;
            }
        }
    }

    private void markEmitted(PendingEndorserBlock pending) {
        pending.emitted = true;
        pending.cancelTimeout();
        pending.endorserBlock = null;
        pending.transactions = Collections.emptyList();
    }

    private PendingEndorserBlock pendingFor(LeiosPoint point) {
        return pendingByEbHash.computeIfAbsent(ebHash(point), ignored -> new PendingEndorserBlock());
    }

    private Optional<String> announcementEbHash(byte[] cbor) {
        DataItem dataItem = CborSerializationUtil.deserializeOne(cbor);
        if (dataItem instanceof ByteString byteString) {
            dataItem = CborSerializationUtil.deserializeOne(byteString.getBytes());
        }

        if (!(dataItem instanceof Array array)) {
            return Optional.empty();
        }

        List<DataItem> items = array.getDataItems();
        if (items.size() == 2 && items.get(0) instanceof ByteString ebHashBytes
                && items.get(1) instanceof UnsignedInteger
                && ebHashBytes.getBytes().length == LeiosPoint.EB_HASH_LENGTH) {
            return Optional.of(HexUtil.encodeHexString(ebHashBytes.getBytes()));
        }

        if (items.size() >= 2 && items.get(0) instanceof Array) {
            BlockHeader blockHeader = BlockHeaderSerializer.INSTANCE.getBlockHeaderFromHeaderArray(array);
            if (blockHeader.getHeaderBody().getLeiosAnnouncement() != null) {
                return Optional.of(blockHeader.getHeaderBody().getLeiosAnnouncement().getEbHash());
            }
        }

        return Optional.empty();
    }

    private String ebHash(LeiosPoint point) {
        return HexUtil.encodeHexString(point.getEbHash());
    }

    private boolean isListenerMethodOverridden(BlockChainDataListener listener, String methodName,
                                               Class<?> parameterType) {
        if (listener == null) {
            return false;
        }
        try {
            Method method = listener.getClass().getMethod(methodName, parameterType);
            return method.getDeclaringClass() != BlockChainDataListener.class;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private ThreadFactory daemonThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "yaci-leios-sync-coordinator");
            thread.setDaemon(true);
            return thread;
        };
    }

    private static class PendingEndorserBlock {
        private LeiosPoint point;
        private long announcedEbSize;
        private boolean blockRequested;
        private boolean txsOffered;
        private boolean txFetchRequested;
        private boolean emitted;
        private int requestedTxCount;
        private String announcementCbor;
        private EndorserBlock endorserBlock;
        private List<EndorserBlockTx> transactions = Collections.emptyList();
        private ScheduledFuture<?> timeout;

        private void cancelTimeout() {
            if (timeout != null) {
                timeout.cancel(false);
                timeout = null;
            }
        }
    }

    private record TxFetchRequest(LeiosPoint point, LeiosTxBitmap bitmap) {
    }
}
